/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.slaves;

import hudson.ExtensionPoint;
import hudson.model.*;
import jenkins.model.Jenkins;

import static hudson.model.LoadStatistics.DECAY;
import hudson.model.MultiStageTimeSeries.TimeScale;
import hudson.Extension;
import net.jcip.annotations.GuardedBy;

import javax.annotation.Nonnull;
import java.awt.Color;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.List;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.io.IOException;

/**
 * Uses the {@link LoadStatistics} and determines when we need to allocate
 * new {@link Node}s through {@link Cloud}.
 *
 * @author Kohsuke Kawaguchi
 */
public class NodeProvisioner {
    /**
     * The node addition activity in progress.
     */
    public static class PlannedNode {
        /**
         * Used to display this planned node to UI. Should ideally include the identifier unique to the node
         * being provisioned (like the instance ID), but if such an identifier doesn't readily exist, this
         * can be just a name of the template being provisioned (like the machine image ID.)
         */
        public final String displayName;

        /**
         * Used to launch and return a {@link Node} object. {@link NodeProvisioner} will check
         * this {@link Future}'s isDone() method to determine when to finalize this object.
         */
        public final Future<Node> future;

        /**
         * The number of executors that will be provided by the {@link Node} launched by
         * this object. This is used for capacity planning in {@link NodeProvisioner#update}.
         */
        public final int numExecutors;

        /**
         * Construct a PlannedNode instance without {@link Cloud} callback for finalization.
         *
         * @param displayName Used to display this object in the UI.
         * @param future Used to launch a @{link Node} object.
         * @param numExecutors The number of executors that will be provided by the launched {@link Node}.
         */
        public PlannedNode(String displayName, Future<Node> future, int numExecutors) {
            if(displayName==null || future==null || numExecutors<1)  throw new IllegalArgumentException();
            this.displayName = displayName;
            this.future = future;
            this.numExecutors = numExecutors;
        }

        /**
         * Indicate that this {@link PlannedNode} is being finalized.
         *
         * <p>
         * {@link NodeProvisioner} will call this method when it's done with {@link PlannedNode}.
         * This indicates that the {@link PlannedNode}'s work has been completed
         * (successfully or otherwise) and it is about to be removed from the list of pending
         * {@link Node}s to be launched.
         *
         * <p>
         * Create a subtype of this class and override this method to add any necessary behaviour.
         *
         * @since 1.503
         */
        public void spent() {
        }
    }

    /**
     * Load for the label.
     */
    private final LoadStatistics stat;

    /**
     * For which label are we working?
     * Null if this {@link NodeProvisioner} is working for the entire Hudson,
     * for jobs that are unassigned to any particular node.
     */
    private final Label label;

    @GuardedBy("self")
    private final List<PlannedNode> pendingLaunches = new ArrayList<PlannedNode>();

    private transient volatile long lastSuggestedReview;

    /**
     * Exponential moving average of the "planned capacity" over time, which is the number of
     * additional executors being brought up.
     *
     * This is used to filter out high-frequency components from the planned capacity, so that
     * the comparison with other low-frequency only variables won't leave spikes.
     */
    private final MultiStageTimeSeries plannedCapacitiesEMA =
            new MultiStageTimeSeries(Messages._NodeProvisioner_EmptyString(),Color.WHITE,0,DECAY);

    public NodeProvisioner(Label label, LoadStatistics loadStatistics) {
        this.label = label;
        this.stat = loadStatistics;
    }

    /**
     * Nodes that are being launched.
     *
     * @return
     *      Can be empty but never null
     * @since 1.401
     */
    public List<PlannedNode> getPendingLaunches() {
        synchronized (pendingLaunches) {
            return new ArrayList<PlannedNode>(pendingLaunches);
        }
    }

    /**
     * Give the {@link NodeProvisioner} a hint that now would be a good time to think about provisioning some nodes.
     * The hint will be ignored if subjected to excessive pestering by callers.
     *
     * @since 1.415
     */
    public void suggestReviewNow() {
        if (System.currentTimeMillis() > lastSuggestedReview + TimeUnit.SECONDS.toMillis(1)) {
            lastSuggestedReview = System.currentTimeMillis();
            Computer.threadPoolForRemoting.submit(new Runnable() {
                public void run() {
                    update();
                }
            });
        }
    }

    /**
     * Periodically invoked to keep track of the load.
     * Launches additional nodes if necessary.
     */
    private synchronized void update() {
        Jenkins jenkins = Jenkins.getInstance();
        lastSuggestedReview = System.currentTimeMillis();

        // clean up the cancelled launch activity, then count the # of executors that we are about to bring up.
        int plannedCapacitySnapshot = 0;
        List<PlannedNode> completedLaunches = new ArrayList<PlannedNode>();

        synchronized (pendingLaunches) {
            for (Iterator<PlannedNode> itr = pendingLaunches.iterator(); itr.hasNext(); ) {
                PlannedNode f = itr.next();
                if (f.future.isDone()) {
                    completedLaunches.add(f);
                    itr.remove();
                } else {
                    plannedCapacitySnapshot += f.numExecutors;
                }
            }
        }

        for (PlannedNode f : completedLaunches) {
            try {
                Node node = f.future.get();
                for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
                    cl.onComplete(f, node);
                }

                jenkins.addNode(node);
                LOGGER.log(Level.INFO,
                        "{0} provisioning successfully completed. We have now {1,number,integer} computer(s)",
                        new Object[]{f.displayName, jenkins.getComputers().length});
            } catch (InterruptedException e) {
                throw new AssertionError(e); // since we confirmed that the future is already done
            } catch (ExecutionException e) {
                LOGGER.log(Level.WARNING, "Provisioned slave " + f.displayName + " failed to launch", e.getCause());
                for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
                    cl.onFailure(f, e.getCause());
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Provisioned slave " + f.displayName + " failed to launch", e);
                for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
                    cl.onFailure(f, e);
                }
            }

            f.spent();
        }

        float plannedCapacity = plannedCapacitySnapshot;
        plannedCapacitiesEMA.update(plannedCapacity);

        int idleSnapshot = stat.computeIdleExecutors();
        int queueLengthSnapshot = stat.computeQueueLength();

        if (queueLengthSnapshot <= idleSnapshot) {
            LOGGER.log(Level.FINE,
                    "Queue length {0} is less than the idle capacity {1}. No provisioning strategy required",
                    new Object[]{queueLengthSnapshot, idleSnapshot});
        } else {
            StrategyState state =
                    new StrategyState(queueLengthSnapshot, label, idleSnapshot, stat.computeTotalExecutors(),
                            plannedCapacitySnapshot);
            List<Strategy> strategies = Jenkins.getInstance().getExtensionList(Strategy.class);
            for (Strategy strategy : strategies.isEmpty()
                    ? Arrays.<Strategy>asList(new StandardStrategyImpl())
                    : strategies) {
                LOGGER.log(Level.FINER, "Consulting {0} provisioning strategy with state {1}",
                        new Object[]{strategy, state});
                if (StrategyDecision.PROVISIONING_COMPLETED == strategy.apply(state)) {
                    LOGGER.log(Level.FINER, "Provisioning strategy {0} declared provisioning complete",
                            strategy);
                    break;
                }
            }
        }
    }


    /**
     * Represents the decision taken by an individual {@link hudson.slaves.NodeProvisioner.Strategy}.
     * @since 1.588
     */
    public static enum StrategyDecision {
        /**
         * This decision is the default decision and indicates that the {@link hudson.slaves.NodeProvisioner.Strategy}
         * either could not provision sufficient resources or did not take any action. Any remaining strategies
         * will be able to contribute to the ultimate decision.
         */
        CONSULT_REMAINING_STRATEGIES,
        /**
         * This decision indicates that the {@link hudson.slaves.NodeProvisioner.Strategy} has taken sufficient
         * action so as to ensure that the required resources are available, and therefore there is no requirement
         * to consult the remaining strategies. Only return this decision when you are certain that there is no
         * need for additional provisioning actions (i.e. you detected an excess workload and have provisioned enough
         * for that excess workload).
         */
        PROVISIONING_COMPLETED
    }

    /**
     * Extension point for node provisioning strategies.
     * @since 1.588
     */
    public static abstract class Strategy implements ExtensionPoint {

        /**
         * Called by {@link NodeProvisioner#update()} to apply this strategy against the specified state.
         * Any provisioning activities should be recorded by calling
         * {@link hudson.slaves.NodeProvisioner.StrategyState#recordPendingLaunches(java.util.Collection)}
         * This method will be called by a thread that is holding a lock on {@link hudson.slaves.NodeProvisioner}
         * @param state the current state.
         * @return the decision.
         */
        @Nonnull
        @GuardedBy("NodeProvisioner.this")
        public abstract StrategyDecision apply(@Nonnull StrategyState state);

    }

    /**
     * Parameter object for {@link hudson.slaves.NodeProvisioner.Strategy}.
     * Intentionally non-static as we need to reference some fields in {@link hudson.slaves.NodeProvisioner}
     * @since 1.588
     */
    public final class StrategyState {
        /**
         * The label under consideration.
         */
        private final Label label;
        /**
         * The number of items in the queue requiring this {@link #label}.
         */
        private final int queueLengthSnapshot;
        /**
         * The planned capacity for this {@link #label}.
         */
        private final int plannedCapacitySnapshot;
        /**
         * The number of idle executors for this {@link #label}
         */
        private final int idleSnapshot;
        /**
         * The total number of executors for this {@link #label}
         */
        private final int totalSnapshot;
        private final List<PlannedNode> pendingLaunches;
        /**
         * The additional planned capacity for this {@link #label} and provisioned by previous strategies during the
         * current updating of the {@link NodeProvisioner}.
         */
        @GuardedBy("this")
        private int additionalPlannedCapacity;

        /**
         * Should only be instantiated by {@link NodeProvisioner#update()}
         * @param queueLengthSnapshot the queue length.
         * @param label the label.
         * @param idleSnapshot the idle executor count.
         * @param totalSnapshot the totoal executor count.
         * @param plannedCapacitySnapshot the planned executor count.
         */
        private StrategyState(int queueLengthSnapshot, Label label, int idleSnapshot, int totalSnapshot,
                              int plannedCapacitySnapshot) {
            this.queueLengthSnapshot = queueLengthSnapshot;
            this.label = label;
            this.idleSnapshot = idleSnapshot;
            this.totalSnapshot = totalSnapshot;
            this.plannedCapacitySnapshot = plannedCapacitySnapshot;
            pendingLaunches = NodeProvisioner.this.pendingLaunches;
        }

        /**
         * The label under consideration.
         */
        public Label getLabel() {
            return label;
        }

        /**
         * The number of items in the queue requiring this {@link #getLabel()}.
         */
        public int getQueueLengthSnapshot() {
            return queueLengthSnapshot;
        }

        /**
         * The planned capacity for this {@link #getLabel()}.
         */
        public int getPlannedCapacitySnapshot() {
            return plannedCapacitySnapshot;
        }

        /**
         * The number of idle executors for this {@link #getLabel()}
         */
        public int getIdleSnapshot() {
            return idleSnapshot;
        }

        /**
         * The total number of executors for this {@link #getLabel()}
         */
        public int getTotalSnapshot() {
            return totalSnapshot;
        }

        /**
         * The additional planned capacity for this {@link #getLabel()} and provisioned by previous strategies during
         * the current updating of the {@link NodeProvisioner}.
         */
        public synchronized int getAdditionalPlannedCapacity() {
            return additionalPlannedCapacity;
        }

        /**
         * The time series average number of items in the queue requiring this {@link #getLabel()}.
         */
        public float getQueueLengthLatest() {
            return stat.queueLength.getLatest(TIME_SCALE);
        }

        /**
         * The time series average planned capacity for this {@link #getLabel()}.
         */
        public float getPlannedCapacityLatest() {
            return plannedCapacitiesEMA.getLatest(TIME_SCALE);
        }

        /**
         * The time series average number of idle executors for this {@link #getLabel()}
         */
        public float getIdleLatest() {
            return stat.getLatestIdleExecutors(TIME_SCALE);
        }

        /**
         * The time series average total number of executors for this {@link #getLabel()}
         */
        public float getTotalLatest() {
            return stat.totalExecutors.getLatest(TIME_SCALE);
        }

        /**
         * If a {@link hudson.slaves.NodeProvisioner.Strategy} takes some provisioning action, it should record
         * and {@link hudson.slaves.NodeProvisioner.PlannedNode} instances by calling this method.
         *
         * @param plannedNodes the {@link hudson.slaves.NodeProvisioner.PlannedNode} instances.
         */
        public void recordPendingLaunches(PlannedNode... plannedNodes) {
            recordPendingLaunches(Arrays.asList(plannedNodes));
        }

        /**
         * If a {@link hudson.slaves.NodeProvisioner.Strategy} takes some provisioning action, it should record
         * and {@link hudson.slaves.NodeProvisioner.PlannedNode} instances by calling this method.
         *
         * @param plannedNodes the {@link hudson.slaves.NodeProvisioner.PlannedNode} instances.
         */
        public void recordPendingLaunches(Collection<PlannedNode> plannedNodes) {
            int additionalPlannedCapacity = 0;
            for (PlannedNode f : plannedNodes) {
                if (f.future.isDone()) {
                    // if done we should use the actual delivered capacity
                    try {
                        Node node = f.future.get();
                        if (node != null) {
                            additionalPlannedCapacity += node.getNumExecutors();
                        }
                    } catch (InterruptedException e) {
                        // ignore, this will be caught by others later
                    } catch (ExecutionException e) {
                        // ignore, this will be caught by others later
                    }
                } else {
                    additionalPlannedCapacity += f.numExecutors;
                }
            }
            synchronized (pendingLaunches) {
                pendingLaunches.addAll(plannedNodes);
            }
            if (additionalPlannedCapacity > 0) {
                synchronized (this) {
                    this.additionalPlannedCapacity += additionalPlannedCapacity;
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("StrategyState{");
            sb.append("label=").append(label);
            sb.append(", queueLengthSnapshot=").append(queueLengthSnapshot);
            sb.append(", plannedCapacitySnapshot=").append(plannedCapacitySnapshot);
            sb.append(", idleSnapshot=").append(idleSnapshot);
            sb.append(", totalSnapshot=").append(totalSnapshot);
            sb.append(", additionalPlannedCapacity=").append(additionalPlannedCapacity);
            sb.append('}');
            return sb.toString();
        }
    }

    /**
     * The default strategy.
     *
     * @since 1.588
     */
    @Extension
    public static class StandardStrategyImpl extends Strategy {

        /** {@inheritDoc} */
        @Nonnull
        @Override
        public StrategyDecision apply(@Nonnull StrategyState state) {
        /*
            Here we determine how many additional slaves we need to keep up with the load (if at all),
            which involves a simple math.

            Broadly speaking, first we check that all the executors are fully utilized before attempting
            to start any new slave (this also helps to ignore the temporary gap between different numbers,
            as changes in them are not necessarily synchronized --- for example, there's a time lag between
            when a slave launches (thus bringing the planned capacity down) and the time when its executors
            pick up builds (thus bringing the queue length down.)

            Once we confirm that, we compare the # of buildable items against the additional slaves
            that are being brought online. If we have more jobs than our executors can handle, we'll launch a new slave.

            So this computation involves three stats:

              1. # of idle executors
              2. # of jobs that are starving for executors
              3. # of additional slaves being provisioned (planned capacities.)

            To ignore a temporary surge/drop, we make conservative estimates on each one of them. That is,
            we take the current snapshot value, and we take the current exponential moving average (EMA) value,
            and use the max/min.

            This is another measure to be robust against temporary surge/drop in those indicators, and helps
            us avoid over-reacting to stats.

            If we only use the snapshot value or EMA value, tests confirmed that the gap creates phantom
            excessive loads and Hudson ends up firing excessive capacities. In a static system, over the time
            EMA and the snapshot value becomes the same, so this makes sure that in a long run this conservative
            estimate won't create a starvation.
         */

            boolean needSomeWhenNoneAtAll = (state.getIdleSnapshot() == 0)
                    && (state.getTotalSnapshot() + state.getPlannedCapacitySnapshot() + state.getAdditionalPlannedCapacity() == 0)
                    && (state.getQueueLengthSnapshot() > 0);
            float idle = Math.max(state.getIdleLatest(), state.getIdleSnapshot());
            if (idle < MARGIN || needSomeWhenNoneAtAll) {
                // make sure the system is fully utilized before attempting any new launch.

                // this is the amount of work left to be done
                float qlen = Math.min(state.getQueueLengthLatest(), state.getQueueLengthSnapshot());

                // ... and this is the additional executors we've already provisioned.
                float plannedCapacity = Math.max(state.getPlannedCapacityLatest(), state.getPlannedCapacitySnapshot())
                        + state.getAdditionalPlannedCapacity();

                float excessWorkload = qlen - plannedCapacity;
                if (needSomeWhenNoneAtAll && excessWorkload < 1) {
                    // in this specific exceptional case we should just provision right now
                    // the exponential smoothing will delay the build unnecessarily
                    excessWorkload = 1;
                }
                float m = calcThresholdMargin(state.getTotalSnapshot());
                if (excessWorkload > 1 - m) {// and there's more work to do...
                    LOGGER.log(Level.FINE, "Excess workload {0,number,#.###} detected. "
                                    + "(planned capacity={1,number,#.###},"
                                    + "Qlen={2,number,#.###},idle={3,number,#.###}&{4,number,integer},"
                                    + "total={5,number,integer},m={6,number,#.###})",
                            new Object[]{
                                    excessWorkload, plannedCapacity, qlen, idle, state.getIdleSnapshot(),
                                    state.getTotalSnapshot(), m
                            });

                    CLOUD:
                    for (Cloud c : Jenkins.getInstance().clouds) {
                        if (excessWorkload < 0) {
                            break;  // enough slaves allocated
                        }

                        // Make sure this cloud actually can provision for this label.
                        if (c.canProvision(state.getLabel())) {
                            // provisioning a new node should be conservative --- for example if excessWorkload is 1.4,
                            // we don't want to allocate two nodes but just one.
                            // OTOH, because of the exponential decay, even when we need one slave,
                            // excess workload is always
                            // something like 0.95, in which case we want to allocate one node.
                            // so the threshold here is 1-MARGIN, and hence floor(excessWorkload+MARGIN) is needed to
                            // handle this.

                            int workloadToProvision = (int) Math.round(Math.floor(excessWorkload + m));

                            for (CloudProvisioningListener cl : CloudProvisioningListener.all())
                            // consider displaying reasons in a future cloud ux
                            {
                                if (cl.canProvision(c, state.getLabel(), workloadToProvision) != null) {
                                    break CLOUD;
                                }
                            }

                            Collection<PlannedNode> additionalCapacities =
                                    c.provision(state.getLabel(), workloadToProvision);

                            for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
                                cl.onStarted(c, state.getLabel(), additionalCapacities);
                            }

                            for (PlannedNode ac : additionalCapacities) {
                                excessWorkload -= ac.numExecutors;
                                LOGGER.log(Level.INFO, "Started provisioning {0} from {1} with {2,number,integer} "
                                                + "executors. Remaining excess workload: {3,number,#.###}",
                                        new Object[]{ac.displayName, c.name, ac.numExecutors, excessWorkload});
                            }
                            state.recordPendingLaunches(additionalCapacities);
                        }
                    }
                    // we took action, only pass on to other strategies if our action was insufficient
                    return excessWorkload > 1 - m ? StrategyDecision.CONSULT_REMAINING_STRATEGIES : StrategyDecision.PROVISIONING_COMPLETED;
                }
            }
            // if we reach here then the standard strategy obviously decided to do nothing, so let any other strategies
            // take their considerations.
            return StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }

        /**
         * Computes the threshold for triggering an allocation.
         * <p/>
         * <p/>
         * Because the excessive workload value is EMA, even when the snapshot value of the excessive
         * workload is 1, the value never really gets to 1. So we need to introduce a notion of the margin M,
         * where we provision a new node if the EMA of the excessive workload goes beyond 1-M (where M is a small value
         * in the (0,1) range.)
         * <p/>
         * <p/>
         * M effectively controls how long Hudson waits until allocating a new node, in the face of workload.
         * This delay is justified for absorbing temporary ups and downs, and can be interpreted as Hudson
         * holding off provisioning in the hope that one of the existing nodes will become available.
         * <p/>
         * <p/>
         * M can be a constant value, but there's a benefit in adjusting M based on the total current capacity,
         * based on the above justification; that is, if there's no existing capacity at all, holding off
         * an allocation doesn't make much sense, as there won't be any executors available no matter how long we wait.
         * On the other hand, if we have a large number of existing executors, chances are good that some
         * of them become available &mdash; the chance gets better and better as the number of current total
         * capacity increases.
         * <p/>
         * <p/>
         * Therefore, we compute the threshold margin as follows:
         * <p/>
         * <pre>
         *   M(t) = M* + (M0 - M*) alpha ^ t
         * </pre>
         * <p/>
         * ... where:
         * <p/>
         * <ul>
         * <li>M* is the ultimate margin value that M(t) converges to with t->inf,
         * <li>M0 is the value of M(0), the initial value.
         * <li>alpha is the decay factor in (0,1). M(t) converges to M* faster if alpha is smaller.
         * </ul>
         */
        private float calcThresholdMargin(int totalSnapshot) {
            float f = (float) (MARGIN + (MARGIN0 - MARGIN) * Math.pow(MARGIN_DECAY, totalSnapshot));
            // defensively ensure that the threshold margin is in (0,1)
            f = Math.max(f, 0);
            f = Math.min(f, 1);
            return f;
        }
    }

    /**
     * Periodically invoke NodeProvisioners
     */
    @Extension
    public static class NodeProvisionerInvoker extends PeriodicWork {
        /**
         * Give some initial warm up time so that statically connected slaves
         * can be brought online before we start allocating more.
         */
    	 public static int INITIALDELAY = Integer.getInteger(NodeProvisioner.class.getName()+".initialDelay",LoadStatistics.CLOCK*10);
    	 public static int RECURRENCEPERIOD = Integer.getInteger(NodeProvisioner.class.getName()+".recurrencePeriod",LoadStatistics.CLOCK);
    	 
        @Override
        public long getInitialDelay() {
            return INITIALDELAY;
        }

        public long getRecurrencePeriod() {
            return RECURRENCEPERIOD;
        }

        @Override
        protected void doRun() {
            Jenkins h = Jenkins.getInstance();
            h.unlabeledNodeProvisioner.update();
            for( Label l : h.getLabels() )
                l.nodeProvisioner.update();
        }
    }

    private static final Logger LOGGER = Logger.getLogger(NodeProvisioner.class.getName());
    private static final float MARGIN = Integer.getInteger(NodeProvisioner.class.getName()+".MARGIN",10)/100f;
    private static final float MARGIN0 = Math.max(MARGIN, getFloatSystemProperty(NodeProvisioner.class.getName()+".MARGIN0",0.5f));
    private static final float MARGIN_DECAY = getFloatSystemProperty(NodeProvisioner.class.getName()+".MARGIN_DECAY",0.5f);

    // TODO: picker should be selectable
    private static final TimeScale TIME_SCALE = TimeScale.SEC10;

    private static float getFloatSystemProperty(String propName, float defaultValue) {
        String v = System.getProperty(propName);
        if (v!=null)
            try {
                return Float.parseFloat(v);
            } catch (NumberFormatException e) {
                LOGGER.warning("Failed to parse a float value from system property "+propName+". value was "+v);
            }
        return defaultValue;
    }
}

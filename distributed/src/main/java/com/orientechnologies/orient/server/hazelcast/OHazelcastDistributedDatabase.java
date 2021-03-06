/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.server.hazelcast;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;

import com.hazelcast.core.IMap;
import com.hazelcast.core.IQueue;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.OScenarioThreadLocal;
import com.orientechnologies.orient.core.db.OScenarioThreadLocal.RUN_MODE;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.server.config.OServerUserConfiguration;
import com.orientechnologies.orient.server.distributed.*;
import com.orientechnologies.orient.server.distributed.ODistributedRequest.EXECUTION_MODE;
import com.orientechnologies.orient.server.distributed.ODistributedServerLog.DIRECTION;
import com.orientechnologies.orient.server.distributed.task.OAbstractRemoteTask;
import com.orientechnologies.orient.server.distributed.task.OAbstractRemoteTask.QUORUM_TYPE;
import com.orientechnologies.orient.server.distributed.task.OResynchTask;

/**
 * Hazelcast implementation of distributed peer. There is one instance per database. Each node creates own instance to talk with
 * each others.
 * 
 * @author Luca Garulli (l.garulli--at--orientechnologies.com)
 * 
 */
public class OHazelcastDistributedDatabase implements ODistributedDatabase {

  protected final OHazelcastPlugin                        manager;
  protected final OHazelcastDistributedMessageService     msgService;

  protected final String                                  databaseName;
  protected final static Map<String, IQueue<?>>           queues                     = new HashMap<String, IQueue<?>>();
  protected final Lock                                    requestLock;

  protected volatile ODatabaseDocumentTx                  database;

  public static final String                              NODE_QUEUE_PREFIX          = "orientdb.node.";
  public static final String                              NODE_QUEUE_REQUEST_POSTFIX = ".request";
  public static final String                              NODE_QUEUE_UNDO_POSTFIX    = ".undo";

  private static final String                             NODE_LOCK_PREFIX           = "orientdb.reqlock.";
  protected volatile Class<? extends OAbstractRemoteTask> waitForTaskType;
  protected AtomicBoolean                                 status                     = new AtomicBoolean(false);
  protected Object                                        waitForOnline              = new Object();
  protected Thread                                        listenerThread;

  public OHazelcastDistributedDatabase(final OHazelcastPlugin manager, final OHazelcastDistributedMessageService msgService,
      final String iDatabaseName) {
    this.manager = manager;
    this.msgService = msgService;
    this.databaseName = iDatabaseName;

    this.requestLock = manager.getHazelcastInstance().getLock(NODE_LOCK_PREFIX + iDatabaseName);

    long resyncEvery = manager.getDatabaseConfiguration(databaseName).getResyncEvery();
    if (resyncEvery > 0) {
      resyncEvery *= 1000; // TRANSFORM IN SECONDS
      // CREATE A TIMER TASK TO RESYNCH
      Orient.instance().getTimer().schedule(new TimerTask() {
        @Override
        public void run() {
          resynch();
        }
      }, resyncEvery, resyncEvery);
    }

    checkLocalNodeInConfiguration();
  }

  @Override
  public ODistributedResponse send2Nodes(final ODistributedRequest iRequest, final Set<String> nodes) {
    final String databaseName = iRequest.getDatabaseName();
    final String clusterName = iRequest.getClusterName();

    if (nodes.isEmpty()) {
      ODistributedServerLog.error(this, getLocalNodeName(), null, DIRECTION.OUT,
          "No nodes configured for partition '%s.%s' request: %s", databaseName, clusterName, iRequest);
      throw new ODistributedException("No nodes configured for partition '" + databaseName + "." + clusterName + "' request: "
          + iRequest);
    }

    final IQueue<ODistributedRequest>[] reqQueues = getRequestQueues(databaseName, nodes);

    final ODistributedConfiguration cfg = manager.getDatabaseConfiguration(databaseName);
    int quorum = calculateQuorum(iRequest, clusterName, cfg, nodes);

    iRequest.setSenderNodeName(manager.getLocalNodeName());

    int availableNodes = 0;
    for (String node : nodes) {
      if (manager.isNodeAvailable(node))
        availableNodes++;
      else {
        if (ODistributedServerLog.isDebugEnabled())
          ODistributedServerLog.debug(this, getLocalNodeName(), node, DIRECTION.OUT,
              "skip listening of response because node '%s' is not online", node);
      }
    }

    final int queueSize = nodes.size();
    int expectedSynchronousResponses = quorum > 0 ? Math.min(quorum, availableNodes) : 1;
    if (iRequest.getTask().getResultStrategy() == OAbstractRemoteTask.RESULT_STRATEGY.UNION)
      expectedSynchronousResponses = availableNodes;

    final boolean waitLocalNode = nodes.contains(manager.getLocalNodeName()) && cfg.isReadYourWrites(clusterName);

    // CREATE THE RESPONSE MANAGER
    final ODistributedResponseManager currentResponseMgr = new ODistributedResponseManager(manager, iRequest, nodes,
        expectedSynchronousResponses, quorum, waitLocalNode,
        iRequest.getTask().getSynchronousTimeout(expectedSynchronousResponses), iRequest.getTask().getTotalTimeout(queueSize));

    msgService.registerRequest(iRequest.getId(), currentResponseMgr);

    if (ODistributedServerLog.isDebugEnabled())
      ODistributedServerLog.debug(this, getLocalNodeName(), nodes.toString(), DIRECTION.OUT, "request %s", iRequest.getTask());

    final long timeout = OGlobalConfiguration.DISTRIBUTED_QUEUE_TIMEOUT.getValueAsLong();

    try {
      requestLock.lock();
      try {
        // LOCK = ASSURE MESSAGES IN THE QUEUE ARE INSERTED SEQUENTIALLY AT CLUSTER LEVEL
        // BROADCAST THE REQUEST TO ALL THE NODE QUEUES
        for (IQueue<ODistributedRequest> queue : reqQueues) {
          queue.offer(iRequest, timeout, TimeUnit.MILLISECONDS);
        }

      } finally {
        requestLock.unlock();
      }

      Orient
          .instance()
          .getProfiler()
          .updateCounter("distributed.replication." + databaseName + ".msgSent",
              "Number of replication messages sent from current node", +1, "distributed.replication.*.msgSent");

      return collectResponses(iRequest, currentResponseMgr);

    } catch (Throwable e) {
      throw new ODistributedException("Error on sending distributed request against database '" + databaseName
          + (clusterName != null ? ":" + clusterName : "") + "'", e);
    }
  }

  @Override
  public ODistributedResponse send(final ODistributedRequest iRequest) {
    final String databaseName = iRequest.getDatabaseName();
    final String clusterName = iRequest.getClusterName();
    final ODistributedConfiguration cfg = manager.getDatabaseConfiguration(databaseName);

    final ODistributedPartitioningStrategy strategy = manager.getPartitioningStrategy(cfg.getPartitionStrategy(clusterName));
    final ODistributedPartition partition = strategy.getPartition(manager, databaseName, clusterName);
    final Set<String> nodes = partition.getNodes();

    return send2Nodes(iRequest, nodes);
  }

  protected void resynch() {
    final long startTimer = System.currentTimeMillis();

    try {
      send(new OHazelcastDistributedRequest(manager.getLocalNodeName(), databaseName, null, new OResynchTask(),
          EXECUTION_MODE.RESPONSE));
    } catch (ODistributedException e) {
      // HIDE EXCEPTION IF ANY ERROR ON QUORUM
    }

    Orient
        .instance()
        .getProfiler()
        .stopChrono("distributed.replication." + databaseName + ".resynch", "Synchronization time among all the nodes", startTimer,
            "distributed.replication.*.resynch");
  }

  protected int calculateQuorum(final ODistributedRequest iRequest, final String clusterName, final ODistributedConfiguration cfg,
      final Collection<String> nodes) {
    final QUORUM_TYPE quorumType = iRequest.getTask().getQuorumType();

    final int queueSize = nodes.size();

    int quorum = 0;
    switch (quorumType) {
    case NONE:
      quorum = 0;
      break;
    case READ:
      quorum = cfg.getReadQuorum(clusterName);
      break;
    case WRITE:
      quorum = cfg.getWriteQuorum(clusterName);
      break;
    case ALL:
      quorum = queueSize;
      break;
    }

    if (quorum > queueSize) {
      final boolean failureAvailableNodesLessQuorum = cfg.getFailureAvailableNodesLessQuorum(clusterName);
      if (failureAvailableNodesLessQuorum)
        throw new ODistributedException(
            "Quorum cannot be reached because it is major than available nodes and 'failureAvailableNodesLessQuorum' settings is true");
      else {
        // SET THE QUORUM TO THE AVAILABLE NODE SIZE
        ODistributedServerLog.debug(this, getLocalNodeName(), nodes.toString(), DIRECTION.OUT,
            "quorum less then available nodes, downgrade quorum to %d", queueSize);
        quorum = queueSize;
      }
    }

    return quorum;
  }

  protected ODistributedResponse collectResponses(final ODistributedRequest iRequest,
      final ODistributedResponseManager currentResponseMgr) throws InterruptedException {
    if (iRequest.getExecutionMode() == EXECUTION_MODE.NO_RESPONSE)
      return null;

    final long beginTime = System.currentTimeMillis();

    // WAIT FOR THE MINIMUM SYNCHRONOUS RESPONSES (WRITE QUORUM)
    if (!currentResponseMgr.waitForSynchronousResponses()) {
      ODistributedServerLog.warn(this, getLocalNodeName(), null, DIRECTION.IN,
          "timeout (%dms) on waiting for synchronous responses from nodes=%s responsesSoFar=%s request=%s",
          System.currentTimeMillis() - beginTime, currentResponseMgr.getExpectedNodes(), currentResponseMgr.getRespondingNodes(),
          iRequest);
    }

    if (currentResponseMgr.isWaitForLocalNode() && !currentResponseMgr.isReceivedCurrentNode())
      ODistributedServerLog.warn(this, getLocalNodeName(), manager.getLocalNodeName(), DIRECTION.IN,
          "no response received from local node about request %s", iRequest);

    // QUORUM REACHED
    return currentResponseMgr.getResponse(iRequest.getTask().getResultStrategy());
  }

  public OHazelcastDistributedDatabase configureDatabase(final ODatabaseDocumentTx iDatabase, final boolean iRestoreMessages,
      final boolean iUnqueuePendingMessages) {
    // CREATE A QUEUE PER DATABASE
    final String queueName = OHazelcastDistributedMessageService.getRequestQueueName(manager.getLocalNodeName(), databaseName);
    final IQueue<ODistributedRequest> requestQueue = msgService.getQueue(queueName);

    if (ODistributedServerLog.isDebugEnabled())
      ODistributedServerLog.debug(this, getLocalNodeName(), null, DIRECTION.NONE, "listening for incoming requests on queue: %s",
          queueName);

    // UNDO PREVIOUS MESSAGE IF ANY
    final IMap<Object, Object> undoMap = restoreMessagesBeforeFailure(iRestoreMessages);

    msgService.checkForPendingMessages(requestQueue, queueName, iUnqueuePendingMessages);

    listenerThread = new Thread(new Runnable() {
      @Override
      public void run() {
        while (!Thread.interrupted()) {
          String senderNode = null;
          ODistributedRequest message = null;
          try {
            message = readRequest(requestQueue);

            // SAVE THE MESSAGE IN THE UNDO MAP IN CASE OF FAILURE
            undoMap.put(databaseName, message);

            if (message != null) {
              senderNode = message.getSenderNodeName();
              onMessage(message);
            }

            // OK: REMOVE THE UNDO BUFFER
            undoMap.remove(databaseName);

          } catch (InterruptedException e) {
            // EXIT CURRENT THREAD
            Thread.interrupted();
            break;

          } catch (Throwable e) {
            ODistributedServerLog.error(this, getLocalNodeName(), senderNode, DIRECTION.IN,
                "error on reading distributed request: %s", e, message != null ? message.getTask() : "-");
          }
        }
      }
    });
    listenerThread.start();

    return this;
  }

  @Override
  public void setOnline() {
    if (database == null) {
      // OPEN IT
      final OServerUserConfiguration replicatorUser = manager.getServerInstance().getUser(
          ODistributedAbstractPlugin.REPLICATOR_USER);
      database = (ODatabaseDocumentTx) manager.getServerInstance().openDatabase("document", databaseName, replicatorUser.name,
          replicatorUser.password);
    }

    status.set(true);

    // WAKE UP ANY WAITERS
    synchronized (waitForOnline) {
      waitForOnline.notifyAll();
    }
  }

  protected void waitForOnline() {
    synchronized (waitForOnline) {
      try {
        waitForOnline.wait();
      } catch (InterruptedException e) {
        Thread.interrupted();
      }
    }
  }

  public OHazelcastDistributedDatabase setWaitForTaskType(Class<? extends OAbstractRemoteTask> iTaskType) {
    waitForTaskType = iTaskType;
    return this;
  }

  protected ODistributedRequest readRequest(final IQueue<ODistributedRequest> requestQueue) throws InterruptedException {
    ODistributedRequest req = requestQueue.take();

    while (waitForTaskType != null) {
      if (req != null) {
        if (req.getTask().getClass().equals(waitForTaskType)) {
          // ARRIVED, RESET IT
          waitForTaskType = null;
          return req;
        } else {
          // SKIP IT
          ODistributedServerLog.debug(this, manager.getLocalNodeName(), req.getSenderNodeName(), DIRECTION.OUT,
              "skip request because the node is not online yet, request=%s sourceNode=%s", req, req.getSenderNodeName());

          // READ THE NEXT ONE
          req = requestQueue.take();
        }
      }
    }

    while (!status.get() && req.getTask().isRequireNodeOnline()) {
      // WAIT UNTIL THE NODE IS ONLINE
      synchronized (waitForOnline) {
        ODistributedServerLog.debug(this, manager.getLocalNodeName(), req.getSenderNodeName(), DIRECTION.OUT,
            "node is not online, request=%s sourceNode=%s must wait to be processed", req, req.getSenderNodeName());

        waitForOnline.wait(5000);
      }
    }

    ODistributedServerLog.debug(this, manager.getLocalNodeName(), req.getSenderNodeName(), DIRECTION.OUT,
        "processing request=%s sourceNode=%s", req, req.getSenderNodeName());

    return req;
  }

  /**
   * Execute the remote call on the local node and send back the result
   */
  protected void onMessage(final ODistributedRequest iRequest) {
    OScenarioThreadLocal.INSTANCE.set(RUN_MODE.RUNNING_DISTRIBUTED);

    try {
      final OAbstractRemoteTask task = iRequest.getTask();

      // EXECUTE IT LOCALLY
      final Serializable responsePayload;
      try {
        ODatabaseRecordThreadLocal.INSTANCE.set(database);
        task.setNodeSource(iRequest.getSenderNodeName());
        responsePayload = manager.executeOnLocalNode(iRequest, database);
      } finally {
        if (database != null)
          database.getLevel1Cache().clear();
      }

      if (ODistributedServerLog.isDebugEnabled())
        ODistributedServerLog.debug(this, manager.getLocalNodeName(), iRequest.getSenderNodeName(), DIRECTION.OUT,
            "sending back response '%s' to request: %s", responsePayload, task);

      final OHazelcastDistributedResponse response = new OHazelcastDistributedResponse(iRequest.getId(),
          manager.getLocalNodeName(), iRequest.getSenderNodeName(), responsePayload);

      try {
        // GET THE SENDER'S RESPONSE QUEUE
        final IQueue<ODistributedResponse> queue = msgService.getQueue(OHazelcastDistributedMessageService
            .getResponseQueueName(iRequest.getSenderNodeName()));

        if (!queue.offer(response, OGlobalConfiguration.DISTRIBUTED_QUEUE_TIMEOUT.getValueAsLong(), TimeUnit.MILLISECONDS))
          throw new ODistributedException("Timeout on dispatching response to the thread queue " + iRequest.getSenderNodeName());

      } catch (Exception e) {
        throw new ODistributedException("Cannot dispatch response to the thread queue " + iRequest.getSenderNodeName(), e);
      }

    } finally {
      OScenarioThreadLocal.INSTANCE.set(RUN_MODE.DEFAULT);
    }
  }

  @SuppressWarnings("unchecked")
  protected IQueue<ODistributedRequest>[] getRequestQueues(final String iDatabaseName, final Collection<String> nodes) {
    final IQueue<ODistributedRequest>[] queues = new IQueue[nodes.size()];

    int i = 0;
    for (String node : nodes)
      queues[i++] = msgService.getQueue(OHazelcastDistributedMessageService.getRequestQueueName(node, iDatabaseName));

    return queues;
  }

  public void shutdown() {
    if (listenerThread != null)
      listenerThread.interrupt();

    try {
      database.close();
    } catch (Exception e) {
    }
  }

  /**
   * Composes the undo queue name based on node name.
   */
  protected String getUndoMapName(final String iDatabaseName) {
    final StringBuilder buffer = new StringBuilder();
    buffer.append(NODE_QUEUE_PREFIX);
    buffer.append(manager.getLocalNodeName());
    if (iDatabaseName != null) {
      buffer.append('.');
      buffer.append(iDatabaseName);
    }
    buffer.append(NODE_QUEUE_UNDO_POSTFIX);
    return buffer.toString();
  }

  protected String getLocalNodeName() {
    return manager.getLocalNodeName();
  }

  protected IMap<Object, Object> restoreMessagesBeforeFailure(final boolean iRestoreMessages) {
    final IMap<Object, Object> undoMap = manager.getHazelcastInstance().getMap(getUndoMapName(databaseName));
    final ODistributedRequest undoRequest = (ODistributedRequest) undoMap.remove(databaseName);
    if (undoRequest != null && iRestoreMessages) {
      ODistributedServerLog.warn(this, getLocalNodeName(), null, DIRECTION.NONE,
          "restore last replication message before the crash for database %s: %s", databaseName, undoRequest);

      try {
        onMessage(undoRequest);
      } catch (Throwable t) {
        ODistributedServerLog.error(this, getLocalNodeName(), null, DIRECTION.NONE,
            "error on executing restored message for database %s", t, databaseName);
      }

    }
    return undoMap;
  }

  public ODatabaseDocumentTx getDatabase() {
    return database;
  }

  protected void checkLocalNodeInConfiguration() {
    final ODistributedConfiguration cfg = manager.getDatabaseConfiguration(databaseName);

    final List<String> foundPartition = cfg.addNewNodeInPartitions(manager.getLocalNodeName());
    if (foundPartition != null) {
      ODistributedServerLog.info(this, manager.getLocalNodeName(), null, DIRECTION.NONE, "adding node '%s' in partition: db=%s %s",
          manager.getLocalNodeName(), databaseName, foundPartition);
      manager.updateCachedDatabaseConfiguration(databaseName, cfg.serialize(), true, true);
    }
  }

  protected void removeNodeInConfiguration(final String iNode, final boolean iForce) {
    // GET DATABASE CFG
    final ODistributedConfiguration cfg = manager.getDatabaseConfiguration(databaseName);

    final List<String> foundPartition = cfg.removeNodeInPartition(iNode, iForce);
    if (foundPartition != null) {
      ODistributedServerLog.info(this, manager.getLocalNodeName(), null, ODistributedServerLog.DIRECTION.NONE,
          "removing node '%s' in partition: db=%s %s", iNode, databaseName, foundPartition);

      msgService.removeQueue(OHazelcastDistributedMessageService.getRequestQueueName(iNode, databaseName));
      manager.updateCachedDatabaseConfiguration(databaseName, cfg.serialize(), true, true);
    }
  }

}

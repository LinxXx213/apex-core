/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.datatorrent.engine;

import com.datatorrent.api.*;
import com.datatorrent.debug.MuxSink;
import com.datatorrent.stram.OperatorDeployInfo;
import com.datatorrent.stram.plan.logical.Operators;
import com.datatorrent.stram.plan.logical.Operators.PortMappingDescriptor;
import com.datatorrent.tuple.CheckpointTuple;
import com.datatorrent.tuple.EndStreamTuple;
import com.datatorrent.tuple.EndWindowTuple;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datatorrent.api.ActivationListener;
import com.datatorrent.api.CheckpointListener;
import com.datatorrent.api.Component;
import com.datatorrent.api.InputOperator;
import com.datatorrent.api.Operator;
import com.datatorrent.api.Sink;
import com.datatorrent.api.StorageAgent;
import com.datatorrent.api.Operator.InputPort;
import com.datatorrent.api.Operator.OutputPort;
import com.datatorrent.api.Operator.Unifier;
import java.io.*;

/**
 *
 * @param <OPERATOR>
 * @author Chetan Narsude <chetan@malhar-inc.com>
 */
public abstract class Node<OPERATOR extends Operator> implements Component<OperatorContext>, Runnable
{
  /**
   * if the Component is capable of taking only 1 input, call it INPUT.
   */
  public static final String INPUT = "input";
  /**
   * if the Component is capable of providing only 1 output, call it OUTPUT.
   */
  public static final String OUTPUT = "output";
  protected int APPLICATION_WINDOW_COUNT; /* this is write once variable */

  protected int CHECKPOINT_WINDOW_COUNT; /* this is write once variable */

  private int id;
  protected final HashMap<String, Sink<Object>> outputs = new HashMap<String, Sink<Object>>();
  @SuppressWarnings(value = "VolatileArrayField")
  protected volatile Sink<Object>[] sinks = Sink.NO_SINKS;
  protected boolean alive;
  protected final OPERATOR operator;
  protected final PortMappingDescriptor descriptor;
  protected long currentWindowId;
  protected long endWindowEmitTime = 0;
  protected long lastSampleCpuTime = 0;
  protected ThreadMXBean tmb;
  protected HashMap<SweepableReservoir, Long> endWindowDequeueTimes = new HashMap<SweepableReservoir, Long>(); // end window dequeue time for input ports
  protected long checkpointedWindowId;
  public int applicationWindowCount;
  public int checkpointWindowCount;

  public Node(OPERATOR operator)
  {
    this.operator = operator;

    descriptor = new PortMappingDescriptor();
    Operators.describe(operator, descriptor);
    tmb = ManagementFactory.getThreadMXBean();
  }

  public Operator getOperator()
  {
    return operator;
  }

  @Override
  public void setup(OperatorContext context)
  {
    operator.setup(context);
//    this is where the ports should be setup but since the
//    portcontext is not available here, we are doing it in
//    StramChild. In future version, we should move that code here
//    for (InputPort<?> port : descriptor.inputPorts.values()) {
//      port.setup(null);
//    }
//
//    for (OutputPort<?> port : descriptor.outputPorts.values()) {
//      port.setup(null);
//    }
  }

  @Override
  public void teardown()
  {
    for (InputPort<?> port : descriptor.inputPorts.values()) {
      port.teardown();
    }

    for (OutputPort<?> port : descriptor.outputPorts.values()) {
      port.teardown();
    }
    operator.teardown();
  }

  public PortMappingDescriptor getPortMappingDescriptor()
  {
    return descriptor;
  }

  public void connectOutputPort(String port, final Sink<Object> sink)
  {
    @SuppressWarnings("unchecked")
    OutputPort<Object> outputPort = (OutputPort<Object>)descriptor.outputPorts.get(port);
    if (outputPort != null) {
      if (sink == null) {
        outputPort.setSink(null);
        outputs.remove(port);
      }
      else {
        outputPort.setSink(sink);
        outputs.put(port, sink);
      }
    }
  }

  public abstract void connectInputPort(String port, final SweepableReservoir reservoir);

  @SuppressWarnings({"unchecked"})
  public void addSinks(Map<String, Sink<Object>> sinks)
  {
    for (Entry<String, Sink<Object>> e : sinks.entrySet()) {
      /* make sure that we ignore all the input ports */
      OutputPort<?> port = descriptor.outputPorts.get(e.getKey());
      if (port == null) {
        continue;
      }

      Sink<Object> ics = outputs.get(e.getKey());
      if (ics == null) {
        port.setSink(e.getValue());
        outputs.put(e.getKey(), e.getValue());
      }
      else if (ics instanceof MuxSink) {
        ((MuxSink)ics).add(e.getValue());
      }
      else {
        MuxSink muxSink = new MuxSink(ics, e.getValue());
        port.setSink(muxSink);
        outputs.put(e.getKey(), muxSink);
      }
    }
  }

  @SuppressWarnings({"unchecked"})
  public void removeSinks(Map<String, Sink<Object>> sinks)
  {
    for (Entry<String, Sink<Object>> e : sinks.entrySet()) {
      /* make sure that we ignore all the input ports */
      OutputPort<?> port = descriptor.outputPorts.get(e.getKey());
      if (port == null) {
        continue;
      }

      Sink<Object> ics = outputs.get(e.getKey());
      if (ics == e.getValue()) {
        port.setSink(null);
        outputs.remove(e.getKey());
      }
      else if (ics instanceof MuxSink) {
        MuxSink ms = (MuxSink)ics;
        ms.remove(e.getValue());
        Sink<Object>[] sinks1 = ms.getSinks();
        if (sinks1.length == 0) {
          port.setSink(null);
          outputs.remove(e.getKey());
        }
        else if (sinks1.length == 1) {
          port.setSink(sinks1[0]);
          outputs.put(e.getKey(), sinks1[0]);
        }
      }
    }
  }

  protected OperatorContext context;

  @SuppressWarnings("unchecked")
  public void activate(OperatorContext context)
  {
    boolean activationListener = operator instanceof ActivationListener;

    activateSinks();
    alive = true;
    this.context = context;
    APPLICATION_WINDOW_COUNT = context.attrValue(OperatorContext.APPLICATION_WINDOW_COUNT, 1);
    CHECKPOINT_WINDOW_COUNT = context.attrValue(OperatorContext.CHECKPOINT_WINDOW_COUNT, 1);

    if (activationListener) {
      ((ActivationListener)operator).activate(context);
    }

    run();

    if (activationListener) {
      ((ActivationListener)operator).deactivate();
    }

    this.context = null;
    emitEndStream();
    deactivateSinks();
  }

  public void deactivate()
  {
    alive = false;
  }

  @Override
  public String toString()
  {
    return String.valueOf(getId());
  }

  protected void emitEndStream()
  {
//    logger.debug("{} sending EndOfStream", this);
    /*
     * since we are going away, we should let all the downstream operators know that.
     */
    EndStreamTuple est = new EndStreamTuple(currentWindowId);
    for (final Sink<Object> output : outputs.values()) {
      output.put(est);
    }
  }

  protected void emitEndWindow()
  {
    // This function currently only gets called upon END_STREAM.
    // DO NOT assume this will get called to emit an end window tuple
    EndWindowTuple ewt = new EndWindowTuple(currentWindowId);
    for (final Sink<Object> output : outputs.values()) {
      output.put(ewt);
    }
  }

  public void emitCheckpoint(long windowId)
  {
    CheckpointTuple ct = new CheckpointTuple(windowId);
    ct.setWindowId(currentWindowId);
    for (final Sink<Object> output : outputs.values()) {
      output.put(ct);
    }
  }

  protected void handleRequests(long windowId)
  {
    endWindowEmitTime = System.currentTimeMillis();
    OperatorStats stats = new OperatorStats();
    reportStats(stats);
    stats.checkpointedWindowId = checkpointedWindowId;
    context.report(stats, windowId);

    /*
     * we prefer to cater to requests at the end of the window boundary.
     */
    try {
      BlockingQueue<OperatorContext.NodeRequest> requests = context.getRequests();
      int size;
      if ((size = requests.size()) > 0) {
        while (size-- > 0) {
          //logger.debug("endwindow: " + t.getWindowId() + " lastprocessed: " + context.getLastProcessedWindowId());
          requests.remove().execute(operator, context.getId(), windowId);
        }
      }
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected void reportStats(OperatorStats stats)
  {
    stats.outputPorts = new ArrayList<OperatorStats.PortStats>();
    for (Entry<String, Sink<Object>> e : outputs.entrySet()) {
      //logger.info("end window emit time is {}", endWindowEmitTime);
      stats.outputPorts.add(new OperatorStats.PortStats(e.getKey(), e.getValue().getCount(true), endWindowEmitTime));
    }

    long currentCpuTime = tmb.getCurrentThreadCpuTime();
    stats.cpuTimeUsed = currentCpuTime - lastSampleCpuTime;
    lastSampleCpuTime = currentCpuTime;
  }

  protected void activateSinks()
  {
    int size = outputs.size();
    if (size == 0) {
      sinks = Sink.NO_SINKS;
    }
    else {
      @SuppressWarnings("unchecked")
      Sink<Object>[] newSinks = (Sink<Object>[])Array.newInstance(Sink.class, size);
      for (Sink<Object> s : outputs.values()) {
        newSinks[--size] = s;
      }

      sinks = newSinks;
    }
  }

  protected void deactivateSinks()
  {
    sinks = Sink.NO_SINKS;
  }

  public boolean isAlive()
  {
    return alive;
  }

  public long getBackupWindowId()
  {
    return checkpointedWindowId;
  }

  public boolean isApplicationWindowBoundary()
  {
    return applicationWindowCount == 0;
  }

  public static void storeNode(OutputStream stream, Node<?> node) throws IOException
  {
    OperatorWrapper ow = new OperatorWrapper();
    ow.operator = node.operator;
    ow.windowCount = node.applicationWindowCount;
    storeOperatorWrapper(stream, ow);
  }

  public static void storeOperator(OutputStream stream, Operator operator) throws IOException
  {
    OperatorWrapper ow = new OperatorWrapper();
    ow.operator = operator;
    ow.windowCount = 0;
    storeOperatorWrapper(stream, ow);
  }

  private static void storeOperatorWrapper(OutputStream stream, OperatorWrapper ow) throws IOException
  {
    Output output = new Output(4096, Integer.MAX_VALUE);
    output.setOutputStream(stream);
    final Kryo k = new Kryo();
    k.writeClassAndObject(output, ow);
    output.flush();
  }

  private static OperatorWrapper retrieveOperatorWrapper(InputStream stream)
  {
    final Kryo k = new Kryo();
    k.setClassLoader(Thread.currentThread().getContextClassLoader());
    Input input = new Input(stream);
    return (OperatorWrapper)k.readClassAndObject(input);
  }

  protected boolean checkpoint(long windowId)
  {
    StorageAgent ba = context.getAttributes().attr(OperatorContext.STORAGE_AGENT).get();
    if (ba != null) {
      try {
        OutputStream stream = ba.getSaveStream(id, windowId);
        Node.storeNode(stream, this);
        stream.close();

        checkpointedWindowId = windowId;
        if (operator instanceof CheckpointListener) {
          ((CheckpointListener)operator).checkpointed(checkpointedWindowId);
        }
        return true;
      }
      catch (IOException ie) {
        throw new RuntimeException(ie);
      }
    }

    return false;
  }

  public static Operator retrieveOperator(InputStream stream)
  {
    return retrieveOperatorWrapper(stream).operator;
  }

  @SuppressWarnings("unchecked")
  public static Node<?> retrieveNode(InputStream stream, OperatorDeployInfo.OperatorType type)
  {
    OperatorWrapper ow = retrieveOperatorWrapper(stream);

    Node<?> node;
    if (ow.operator instanceof InputOperator && type == OperatorDeployInfo.OperatorType.INPUT) {
      node = new InputNode((InputOperator)ow.operator);
    }
    else if (ow.operator instanceof Unifier && type == OperatorDeployInfo.OperatorType.UNIFIER) {
      node = new UnifierNode((Unifier<Object>)ow.operator);
    }
    else {
      node = new GenericNode(ow.operator);
    }

    node.applicationWindowCount = ow.windowCount;
    return node;
  }

  /**
   * @return the id
   */
  public int getId()
  {
    return id;
  }

  /**
   * @param id the id to set
   */
  public void setId(int id)
  {
    if (this.id == 0) {
      this.id = id;
    }
    else {
      throw new RuntimeException("Id cannot be changed from " + this.id + " to " + id);
    }
  }

  public static class OperatorWrapper
  {
    public Operator operator;
    public int windowCount;
  }

  private static final Logger logger = LoggerFactory.getLogger(Node.class);
}
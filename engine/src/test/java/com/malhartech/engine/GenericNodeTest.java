/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.engine;

import com.malhartech.api.*;
import com.malhartech.bufferserver.Buffer.Message.MessageType;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import junit.framework.Assert;
import org.junit.Test;

/**
 *
 * @author Chetan Narsude <chetan@malhar-inc.com>
 */
public class GenericNodeTest
{
  long beginWindowId;
  long endWindowId;

  class GenericOperator implements Operator
  {
    DefaultInputPort<Object> ip1 = new DefaultInputPort<Object>(this)
    {
      @Override
      public void process(Object tuple)
      {
        op.emit(tuple);
      }

    };
    DefaultInputPort<Object> ip2 = new DefaultInputPort<Object>(this)
    {
      @Override
      public void process(Object tuple)
      {
        op.emit(tuple);
      }

    };
    DefaultOutputPort<Object> op = new DefaultOutputPort<Object>(this);

    @Override
    public void beginWindow(long windowId)
    {
      beginWindowId = windowId;
    }

    @Override
    public void endWindow()
    {
      endWindowId = beginWindowId;
    }

    @Override
    public void setup(Context.OperatorContext context)
    {
      throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void teardown()
    {
      throw new UnsupportedOperationException("Not supported yet.");
    }

  }

  @Test
  @SuppressWarnings("SleepWhileInLoop")
  public void testSomeMethod() throws InterruptedException
  {
    long sleeptime = 20L;
    final ArrayList<Object> list = new ArrayList<Object>();
    GenericOperator go = new GenericOperator();
    final GenericNode gn = new GenericNode("GenericNode", go);
    Sink<Object> output = new Sink<Object>()
    {
      @Override
      public void process(Object tuple)
      {
        list.add(tuple);
      }

    };

    @SuppressWarnings("unchecked")
    Sink<Object> input1 = (Sink<Object>)gn.connectInputPort("ip1", output);
    @SuppressWarnings("unchecked")
    Sink<Object> input2 = (Sink<Object>)gn.connectInputPort("ip2", output);
    gn.connectOutputPort("op", output);

    final AtomicBoolean ab = new AtomicBoolean(false);
    Thread t = new Thread()
    {
      @Override
      public void run()
      {
        ab.set(true);
        gn.activate(new OperatorContext("GenericOperator", this, null));
      }

    };
    t.start();

    do {
      Thread.sleep(sleeptime);
    }
    while (ab.get() == false);


    Tuple beginWindow1 = new Tuple(MessageType.BEGIN_WINDOW);
    beginWindow1.windowId = 0x1L;

    input1.process(beginWindow1);
    Thread.sleep(sleeptime);
    Assert.assertEquals(1, list.size());

    input2.process(beginWindow1);
    Thread.sleep(sleeptime);
    Assert.assertEquals(1, list.size());

    Tuple endWindow1 = new EndWindowTuple();
    endWindow1.windowId = 0x1L;

    input1.process(endWindow1);
    Thread.sleep(sleeptime);
    Assert.assertEquals(1, list.size());

    Tuple beginWindow2 = new Tuple(MessageType.BEGIN_WINDOW);
    beginWindow2.windowId = 0x2L;

    input1.process(beginWindow2);
    Thread.sleep(sleeptime);
    Assert.assertEquals(1, list.size());

    input2.process(endWindow1);
    Thread.sleep(sleeptime);
    Assert.assertEquals(3, list.size());

    input2.process(beginWindow2);
    Thread.sleep(sleeptime);
    Assert.assertEquals(3, list.size());

    Tuple endWindow2 = new EndWindowTuple();
    endWindow2.windowId = 0x2L;

    input2.process(endWindow2);
    Thread.sleep(sleeptime);
    Assert.assertEquals(3, list.size());

    input1.process(endWindow2);
    Thread.sleep(sleeptime);
    Assert.assertEquals(4, list.size());

    EndStreamTuple est = new EndStreamTuple();

    input1.process(est);
    Thread.sleep(sleeptime);
    Assert.assertEquals(4, list.size());

    Tuple beginWindow3 = new Tuple(MessageType.BEGIN_WINDOW);
    beginWindow3.windowId = 0x3L;

    input2.process(beginWindow3);
    Thread.sleep(sleeptime);
    Assert.assertEquals(5, list.size());

    Tuple endWindow3 = new EndWindowTuple();
    endWindow3.windowId = 0x3L;

    input2.process(endWindow3);
    Thread.sleep(sleeptime);
    Assert.assertEquals(6, list.size());

    Assert.assertNotSame(Thread.State.TERMINATED, t.getState());

    input2.process(est);
    Thread.sleep(sleeptime);
    Assert.assertEquals(7, list.size());

    Thread.sleep(sleeptime);

    Assert.assertEquals(Thread.State.TERMINATED, t.getState());
  }

}
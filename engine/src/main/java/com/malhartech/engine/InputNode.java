/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.engine;

import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.malhartech.api.InputOperator;
import com.malhartech.api.Sink;
import com.malhartech.tuple.Tuple;

/**
 *
 * @author Chetan Narsude <chetan@malhar-inc.com>
 */
public class InputNode extends Node<InputOperator>
{
  private ArrayList<SweepableReservoir> deferredInputConnections = new ArrayList<SweepableReservoir>();
  protected SweepableReservoir controlTuples;

  public InputNode(InputOperator operator)
  {
    super(operator);
  }

  @Override
  public void connectInputPort(String port, SweepableReservoir reservoir)
  {
    if (Node.INPUT.equals(port)) {
      if (controlTuples == null) {
        controlTuples = reservoir;
      }
      else {
        deferredInputConnections.add(reservoir);
      }
    }
  }

  @Override
  @SuppressWarnings(value = "SleepWhileInLoop")
  public final void run()
  {
    long spinMillis = context.attrValue(OperatorContext.SPIN_MILLIS, 10);
    boolean insideWindow = false;
    boolean checkpoint = false;

    try {
      while (alive) {
        Tuple t = controlTuples.sweep();
        if (t == null) {
          if (insideWindow) {
            int generatedTuples = 0;

            for (Sink<Object> cs : sinks) {
              generatedTuples -= cs.getCount(false);
            }

            operator.emitTuples();

            for (Sink<Object> cs : sinks) {
              generatedTuples += cs.getCount(false);
            }

            if (generatedTuples == 0) {
              Thread.sleep(spinMillis);
            }
          }
          else {
            Thread.sleep(0);
          }
        }
        else {
          controlTuples.remove();
          switch (t.getType()) {
            case BEGIN_WINDOW:
              for (int i = sinks.length; i-- > 0;) {
                sinks[i].put(t);
              }
              currentWindowId = t.getWindowId();
              if (applicationWindowCount == 0) {
                insideWindow = true;
                operator.beginWindow(currentWindowId);
              }
              operator.emitTuples(); /* give at least one chance to emit the tuples */
              break;

            case END_WINDOW:
              if (++applicationWindowCount == APPLICATION_WINDOW_COUNT) {
                operator.endWindow();
                insideWindow = false;
                applicationWindowCount = 0;
              }

              for (int i = sinks.length; i-- > 0;) {
                sinks[i].put(t);
              }

              if (++checkpointWindowCount == CHECKPOINT_WINDOW_COUNT) {
                if (checkpoint && checkpoint(currentWindowId)) {
                  checkpoint = false;
                }
                checkpointWindowCount = 0;
              }
              handleRequests(currentWindowId);
              break;

            case CHECKPOINT:
              if (checkpointWindowCount == 0) {
                checkpoint(currentWindowId);
              }
              else {
                checkpoint = true;
              }
              for (int i = sinks.length; i-- > 0;) {
                sinks[i].put(t);
              }
              break;

            case END_STREAM:
              if (deferredInputConnections.isEmpty()) {
                for (int i = sinks.length; i-- > 0;) {
                  sinks[i].put(t);
                }
                alive = false;
              }
              else {
                controlTuples = deferredInputConnections.remove(0);
              }
              break;

            default:
              for (int i = sinks.length; i-- > 0;) {
                sinks[i].put(t);
              }
              break;
          }
        }
      }
    }
    catch (InterruptedException ex) {
      alive = false;
    }
    catch (RuntimeException ex) {
      if (ex.getCause() instanceof InterruptedException) {
        alive = false;
      }
      else {
        throw ex;
      }
    }

    if (insideWindow) {
      operator.endWindow();
      if (++applicationWindowCount == APPLICATION_WINDOW_COUNT) {
        applicationWindowCount = 0;
      }
      if (++checkpointWindowCount == CHECKPOINT_WINDOW_COUNT) {
        if (checkpoint) {
          checkpoint(currentWindowId);
        }
        checkpointWindowCount = 0;
      }
      handleRequests(currentWindowId);
    }
  }

  private static final Logger logger = LoggerFactory.getLogger(InputNode.class);
}

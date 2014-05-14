/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap

import org.apache.spark.executor.ShuffleReadMetrics
import org.apache.spark.serializer.Serializer
import org.apache.spark.storage.{BlockFetcherIterator, BlockId, BlockManagerId, ShuffleBlockId}
import org.apache.spark.util.CompletionIterator

private[spark] class BlockStoreShuffleFetcher extends ShuffleFetcher with Logging {

  override def fetch[T](
      shuffleId: Int,
      reduceId: Int,
      context: TaskContext,
      serializer: Serializer)
    : Iterator[T] =
  {

    logDebug("Fetching outputs for shuffle %d, reduce %d".format(shuffleId, reduceId))
    val blockManager = SparkEnv.get.blockManager
    val mapOutputTracker = SparkEnv.get.mapOutputTracker

    val startTime = System.currentTimeMillis
    var statuses = mapOutputTracker.getServerStatuses(shuffleId, reduceId)
    logDebug("Fetching map output location for shuffle %d, reduce %d took %d ms".format(
      shuffleId, reduceId, System.currentTimeMillis - startTime))

    val splitsByAddress = new HashMap[BlockManagerId, ArrayBuffer[(Int, Long)]]

    for (((address, size), index) <- statuses.zipWithIndex if address != null) {
      splitsByAddress.getOrElseUpdate(address, ArrayBuffer()) += ((index, size))
    }
    //track the map outputs we're missing
    var missingMapOutputs = statuses.zipWithIndex.filter(_._1._1 == null).map(_._2)

    val blocksByAddress: Seq[(BlockManagerId, Seq[(BlockId, Long)])] = splitsByAddress.toSeq.map {
      case (address, splits) =>
        (address, splits.map(s => (ShuffleBlockId(shuffleId, s._1, reduceId), s._2)))
    }

    def unpackBlock(blockPair: (BlockId, Option[Iterator[Any]])) : Iterator[T] = {
      val blockId = blockPair._1
      val blockOption = blockPair._2
      blockOption match {
        case Some(block) => {
          block.asInstanceOf[Iterator[T]]
        }
        case None => {
          blockId match {
            case ShuffleBlockId(shufId, mapId, _) =>
              val address = statuses(mapId.toInt)._1
              throw new FetchFailedException(address, shufId.toInt, mapId.toInt, reduceId, null)
            case _ =>
              throw new SparkException(
                "Failed to get block " + blockId + ", which is not a shuffle block")
          }
        }
      }
    }

    val blockFetcherItr = blockManager.getMultiple(blocksByAddress, serializer)
    var itr = blockFetcherItr.flatMap(unpackBlock)

    //time interval(in second) the thread should sleep
    var sleepInterval = 8.toFloat
    while(!missingMapOutputs.isEmpty){
      val oldMissingNum = missingMapOutputs.size
      logInfo("Still missing " + oldMissingNum + " outputs for reduceId " + reduceId +
        ". Sleep " + sleepInterval + "s. ---lirui")
      Thread.sleep((sleepInterval * 1000).toLong)
      logInfo("Trying to update map output statues for reduceId "+reduceId+" ---lirui")
      mapOutputTracker.updateMapStatusesForShuffle(shuffleId)
      statuses = mapOutputTracker.getServerStatuses(shuffleId, reduceId)
      val missingSplitsByAddress = new HashMap[BlockManagerId, ArrayBuffer[(Int, Long)]]
      for (index <- missingMapOutputs if statuses(index)._1 != null) {
        missingSplitsByAddress.getOrElseUpdate(statuses(index)._1, ArrayBuffer()) += ((index, statuses(index)._2))
      }
      //we have new outputs ready for this reduce
      if(!missingSplitsByAddress.isEmpty){
        val missingBlocksByAddress: Seq[(BlockManagerId, Seq[(BlockId, Long)])] = missingSplitsByAddress.toSeq.map {
          case (address, splits) =>
            (address, splits.map(s => (ShuffleBlockId(shuffleId, s._1, reduceId), s._2)))
        }
        val missingBlockFetcherItr = blockManager.getMultiple(missingBlocksByAddress, serializer)
        itr = itr ++ missingBlockFetcherItr.flatMap(unpackBlock)
      } else {
        logInfo("No updates in the previous interval "+sleepInterval+"s, sleep longer. ---lirui")
      }
      missingMapOutputs = statuses.zipWithIndex.filter(_._1._1 == null).map(_._2)
      val fillingUpSpeed = (oldMissingNum - missingMapOutputs.size).toFloat / sleepInterval
      sleepInterval = if (fillingUpSpeed > 0.01) math.max(10.toFloat, missingMapOutputs.size.toFloat / 5) / fillingUpSpeed else sleepInterval * 2
    }

    val completionIter = CompletionIterator[T, Iterator[T]](itr, {
      val shuffleMetrics = new ShuffleReadMetrics
      shuffleMetrics.shuffleFinishTime = System.currentTimeMillis
      shuffleMetrics.fetchWaitTime = blockFetcherItr.fetchWaitTime
      shuffleMetrics.remoteBytesRead = blockFetcherItr.remoteBytesRead
      shuffleMetrics.totalBlocksFetched = blockFetcherItr.totalBlocks
      shuffleMetrics.localBlocksFetched = blockFetcherItr.numLocalBlocks
      shuffleMetrics.remoteBlocksFetched = blockFetcherItr.numRemoteBlocks
      context.taskMetrics.shuffleReadMetrics = Some(shuffleMetrics)
    })

    new InterruptibleIterator[T](context, completionIter)
  }
}

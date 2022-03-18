/*
 * Copyright 2020 University of California, Riverside
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

package edu.ucr.cs.bdlab

import org.apache.hadoop.fs.{FileSystem, GlobFilter, Path, PathFilter}
import org.apache.spark.SparkContext
import org.apache.spark.sql.connector.read.InputPartition
import org.apache.spark.sql.types._

import java.io.BufferedInputStream
import scala.collection.immutable.{HashMap, HashSet}
import scala.collection.mutable.ArrayBuffer

object Partitioning {

  def getFilePaths(options: JsonOptions): Seq[String] = {
    val hadoopConf = SparkContext.getOrCreate().hadoopConfiguration
    val fs = FileSystem.get(hadoopConf)
    var filter: PathFilter = null
    if (!options.pathGlobFilter.equals("")) {
      filter = new GlobFilter(options.pathGlobFilter).asInstanceOf[PathFilter]
    }
    var filePaths = new ArrayBuffer[String]()
    val hasWildcard = options.filepath contains '*'
    val isDirectory = if (hasWildcard) { false }
    else { fs.getFileStatus(new Path(options.filepath)).isDirectory }

    // println("Searching for matching files in path:")
    if (hasWildcard) {
      val statues = fs.globStatus(new Path(options.filepath), filter)
      for (fileStatus <- statues) {
        val path = fileStatus.getPath().toString()
        filePaths.append(path)
        // println(path)
      }
    } else if (isDirectory) {
      val iterator =
        fs.listFiles(new Path(options.filepath), options.recursive.toBoolean)
      while (iterator.hasNext()) {
        val status = iterator.next()
        val path = status.getPath()
        if (status.isFile && (filter == null || filter.accept(path))) {
          filePaths.append(path.toString)
          // println(path)
        }
      }
    } else {
      filePaths.append(options.filepath)
      // println(options.filepath)
    }

    if (filePaths.length == 0)
      println("No files were found!")

    return filePaths.toSeq
  }

  def getFilePartitions(
      filePaths: Seq[String],
      options: JsonOptions
  ): ArrayBuffer[InputPartition] = {
    var partitions: ArrayBuffer[InputPartition] =
      new ArrayBuffer[InputPartition]()
    val conf = SparkContext
      .getOrCreate()
      .getConf

    // This function attempts to create a number of partitions equal to
    // the available executers within the set size limits. 
    val sparkExecuters = conf.getInt("spark.default.parallelism", 8)
    println("Executers: " + sparkExecuters)
    val sparkMinBucketSize = conf.getLong("spark.sql.files.minPartitionBytes", 33554432)
    println("MinBucketSize: " + sparkMinBucketSize)
    val sparkMaxBucketSize = conf.getLong("spark.sql.files.maxPartitionBytes", 1073741824)
    println("MaxBucketSize: " + sparkMaxBucketSize)

    var totalSize = 0L
    for (path <- filePaths) {
      val (_, fileSize) = Parser.getInputStream(path, options.hdfsPath)
      totalSize += fileSize
    }
    val bucketSize = ((1.0*totalSize/sparkExecuters).ceil.toLong)
                        .min(sparkMaxBucketSize) // shouldn't be more than this
                        .max(sparkMinBucketSize) // shouldn't be less than this
    println("BucketSize: " + bucketSize)
    for (path <- filePaths) {
      val (inputStream, fileSize) =
        Parser.getInputStream(path, options.hdfsPath)
      val nPartitions = (1.0 * fileSize / bucketSize).ceil.toInt

      var startIndex = 0L;
      var endIndex = bucketSize.min(fileSize);
      for (i <- 0 to nPartitions - 1) {
        partitions.append(
          new JsonInputPartition(
            path,
            startIndex,
            endIndex,
            0,
            0
          )
        )
        startIndex = endIndex
        endIndex = (startIndex + bucketSize).min(fileSize)
      }
    }

    return partitions
  }

//  def toNewLine(partition: JsonInputPartition,
//              options: JsonOptions): Unit = {
//    var start = partition.start
//    val end = partition.end
//    val (inputStream, fileSize) =
//      Parser.getInputStream(partition.path, options.hdfsPath)
//      val bufferedReader =
//        Parser.getBufferedReader(
//          inputStream,
//          options.encoding,
//          end
//        )
//
//    var i = bufferedReader.read()
//
//    while(i != -1 && i != '\n' && i != '\r') {
//      start += Parser.charSize(i)
//      i = bufferedReader.read()
//    }
//
//    return new JsonInputPartition(
//      partition.path,
//      start,
//      partition.end,
//      startLevel,
//      startState
//    )
//  }

  def speculate(
      partition: JsonInputPartition,
      options: JsonOptions
  ): JsonInputPartition = {

    var start = partition.start
    var end = partition.end
    var partitionLevel = 0
    var startLevel = 0
    var startState = 0
    var startToken = ""
    var speculationKeys = options.speculationKeys
    val (inputStream, fileSize) =
      Parser.getInputStream(partition.path, options.hdfsPath)
    var dfa = options.getDFA()
    val maxQueryLevel = dfa.states.length

    // shift the start index and determine label and level
    var partitionLabel = ""
    var shiftedEndIndex = start
    if (start > 0) {
      var token = ""
      var partitionLevel = maxQueryLevel
      var partitionState = 0
      partitionLabel = ""
      var skippedLevels = false
      var foundToken = false
      while (!foundToken && shiftedEndIndex < fileSize) {
        val bufferedReader =
          Parser.getBufferedReader(
            inputStream,
            options.encoding,
            shiftedEndIndex
          )
        val (tmpToken, index) =
          Parser.getNextToken(
            bufferedReader,
            options.encoding,
            partition.start,
            partition.end
          )
        if (
          (speculationKeys contains tmpToken)
        ) {
          foundToken = true
          token = tmpToken
          partitionLevel = speculationKeys(token)._1
          partitionState = speculationKeys(token)._2
          partitionLabel = token
          if (partitionLevel > partitionState) {

            shiftedEndIndex = shiftedEndIndex + Parser.skipLevels(
              bufferedReader,
              options.encoding,
              partitionLevel - partitionState,
              fileSize
            ) + 2
            partitionLevel = partitionState
            skippedLevels = true
          }
        }
        if (index == -1) {
          shiftedEndIndex = fileSize
        } else {
          shiftedEndIndex += index + Parser.stringSize(
            token,
            options.encoding
          ) + 2
        }
      }
      if (!skippedLevels) {
        shiftedEndIndex -= Parser.stringSize(
          partitionLabel,
          options.encoding
        ) + 2
      }

      startLevel = partitionLevel
      startState = partitionState
      start = shiftedEndIndex

      if (startState > 0 && startState == startLevel && !skippedLevels) {
        startState -= 1

      }

    }

    return new JsonInputPartition(
      partition.path,
      start,
      partition.end,
      startLevel,
      startState
    )
  }

  def speculation(options: JsonOptions): Array[InputPartition] = {
    val filePaths: Seq[String] = getFilePaths(options)

    val tokenLevelsSorted = options.encounteredTokens
      .filter(x => x._2.size == 1)
      .map(f => (f._1, f._2.head))
      .toSeq
      .sortBy { case (k, (a, b, c)) => (c, a, b) }
      .reverse
    val maxOccurrence = tokenLevelsSorted(0)._2._3
    var speculationKeys = new HashMap[String, (Int, Int, Int)]
    tokenLevelsSorted
      .filter(x => x._2._3 >= 1000)
      .foreach(x => {
      speculationKeys = speculationKeys + (x._1 -> x._2)
      })
    if (speculationKeys.size < 10 && tokenLevelsSorted.size >= 10) {
      speculationKeys = new HashMap[String, (Int, Int, Int)]
      for (i <- 0 to 10) {
        speculationKeys =
          speculationKeys + (tokenLevelsSorted(i)._1 -> tokenLevelsSorted(i)._2)
      }
    }
    println(
      "\n\n\n\n\n#################KEYS AVAILABLE FOR SPECULATION#############"
    )
    println("KEY,LEVEL,DFA STATE,#ENCOUNTERED")
    speculationKeys.map({ case (k, v) =>
      println(k + "," + v._1 + "," + v._2 + "," + v._3)
    })
    println("##########################################\n\n\n\n\n")
    if(speculationKeys.size == 0) {
      throw new RuntimeException("Not possible to speculate. There are no keys that occurred in only one level.")
    }
    options.speculationKeys = speculationKeys

    val partitions = options.partitions

    val sc = SparkContext.getOrCreate()
    val x = sc.makeRDD(partitions.map(p => (p, p.preferredLocations())))

    val y = x.map(partition =>
      speculate(partition._1.asInstanceOf[JsonInputPartition], options)
    )

    val z = y.collect()
    var q: ArrayBuffer[InputPartition] = new ArrayBuffer[InputPartition]()
    var i = z.length - 1
    var prevStart = z(i).end
    var prevPath = ""
    while (i >= 0) {
      val partition = z(i).asInstanceOf[JsonInputPartition]
      val end = if (partition.path.equals(prevPath)) { prevStart }
      else { partition.end }
      q.append(
        new JsonInputPartition(
          partition.path,
          partition.start,
          end,
          partition.startLevel,
          partition.dfaState,
          id=i
        )
      )
      prevStart = partition.start
      prevPath = partition.path
      i -= 1
    }

    q.toArray.reverse
  }

  def mergeSyntaxStack(
      _s1: ArrayBuffer[String],
      _s2: ArrayBuffer[String],
      _s2Positions: ArrayBuffer[Long],
      prevEnd: Long
  ): (ArrayBuffer[String], ArrayBuffer[String], ArrayBuffer[Long]) = {
    var s2 = new ArrayBuffer[String]()
    var s2Positions = new ArrayBuffer[Long]()
    var s3 = new ArrayBuffer[String]()
    var skippedString = false
    var i = 0
    while (i < _s2.size) {
      val pos = _s2Positions(i)
      val elem = _s2(i)
      if (pos > prevEnd) {
        s2.append(elem)
        s2Positions.append(pos)
      }
      i += 1
    }

    for (elem <- _s1) {
      s3.append(elem)
    }
    for (elem <- s2) {
      if (s3.isEmpty) {
        s3.append(elem)
      } else if (elem.equals("}")) {
        if (s3.last.equals("{")) {
          s3.trimEnd(1)
        } else {
          s3.trimEnd(2)
        }
      } else if (elem.equals("]")) {
        s3.trimEnd(1)
      } else if (elem.equals("{")) {
        s3.append(elem)
      } else if (elem.equals("[")) {
        s3.append(elem)
      } else { // key
        s3.append(elem)
      }
    }
    return (s3, s2, s2Positions)
  }


  def skip(
            reader: BufferedInputStream,
            _pos: Long,
            end: Long,
            currentChar: Byte = 0): Long = {

    val ARRAY_START : Byte = 91
    val ARRAY_END : Byte = 93
    val OBJECT_START : Byte = 123
    val OBJECT_END : Byte = 125
    val QUOTE : Byte = 34
    val COMMA : Byte = 44
    val ESCAPE : Byte = 92

    var pos = _pos
    var localStack = new java.util.ArrayList[Byte]()
    var isEscaped = false
    var isString = false
    var countEscape = 0
    var c = QUOTE
    var prevC = QUOTE
    val buf = new Array[Byte](1)

    if (currentChar == 0 || currentChar == COMMA) {
      val i = reader.read(buf, 0, 1)
      if (i == -1) {
        return pos
      }
      c = buf(0)
      pos += 1
    } else {
      c = currentChar;
    }
    while (true) {
      if (localStack.isEmpty && (c == COMMA || c == ARRAY_END || c == OBJECT_END)) {
        reader.reset()
        pos -=  1
        return pos
      } else if (
        !isString &&
          (c == OBJECT_START || c == ARRAY_START ||
            (!isEscaped && c == QUOTE))
      ) {
        localStack.add(c)
        if (c == QUOTE)
          isString = true
      } else if (
        (!isString && (c == OBJECT_END || c == ARRAY_END)) ||
          (isString && !isEscaped && c == QUOTE)
      ) {
        localStack.remove(localStack.size()-1)
        if (c == QUOTE)
          isString = false;
        if (localStack.isEmpty) {
          return pos
        }
      } else {
        if (isString && c == ESCAPE) {
          if (prevC == ESCAPE)
            countEscape += 1;
          else
            countEscape = 1
          if (countEscape % 2 != 0)
            isEscaped = true
          else
            isEscaped = false
        }
      }

      if (c != ESCAPE) {
        isEscaped = false
        countEscape = 0
      }
      prevC = c
      if (pos >= end && localStack.isEmpty) {
        return pos
      }
      reader.mark(1);
      val i = reader.read(buf,0,1)

      if (i == -1) {
        return pos
      }
      c = buf(0)
      pos += 1
    }
    return pos
  }

  def getEndState(
      partition: JsonInputPartition,
      options: JsonOptions
  ): (String, Long, Long, ArrayBuffer[String], ArrayBuffer[Long], Boolean) = {
    val ARRAY_START : Byte = 91
    val ARRAY_END : Byte = 93
    val OBJECT_START : Byte = 123
    val OBJECT_END : Byte = 125
    val QUOTE : Byte = 34
    val COMMA : Byte = 44
    val ESCAPE : Byte = 92
    val SEMI_COLON : Byte = 58
    val WHITESPACE : Array[Byte] = List[Byte](9, 10, 11, 12, 13, 32).toArray



    var syntaxStack: ArrayBuffer[Byte] = new ArrayBuffer[Byte]()
    var syntaxPositions: ArrayBuffer[Long] = new ArrayBuffer[Long]()

    val (inputStream, fileSize) =
      Parser.getInputStream(partition.path, options.hdfsPath)


    if (partition.start == 0 && partition.end == fileSize) {
      // no need for this function for files with one partition
      return (
        partition.path,
        partition.start,
        partition.end,
        new ArrayBuffer[String](),
        syntaxPositions,
        false
      )
    }
    var bufferedReader =
      Parser.getBufferedReader(inputStream, options.encoding, partition.start)
    var pos = partition.start
    var start = partition.start
    var token = ""
    var acceptToken = false
    var isValue = false
    var stackPos: Int = -1
    var stackPosMax: Int = -1
    var append = false
    var appendValue : Byte = 0
    val controlChars = HashSet[Byte](OBJECT_START, OBJECT_END, ARRAY_START, ARRAY_END, QUOTE)

    if(partition.start > 0) {
      val (_token, _pos) = Parser.consume(
        bufferedReader,
        options.encoding,
        pos,
        partition.end,
        '"'
      )
      val isValidString = Parser.isValidString(
        _token
          .substring(1, _token.size - 1)
      )

      

      if (isValidString) { // skip it
        pos = _pos
      } else {
        // TODO: if not valid string search for token and adjust positions
        // Parser.getNextToken (returns the position as well)
        // use that position to determine the correct classification 
        // if start is string or not
        // reset reader to reconsider json characters

        bufferedReader =
          Parser.getBufferedReader(inputStream, options.encoding, pos)
      }
    }

    bufferedReader.close()
    inputStream.seek(pos)
    val byteStream =  new BufferedInputStream(inputStream)
    val buf : Array[Byte] = new Array[Byte](1)

    while (pos < partition.end) {

      val i = byteStream.read(buf, 0, 1)
      val c = buf(0)
      pos += 1
      append = false
      if (c == OBJECT_START) {
        append = true
        appendValue = OBJECT_START
        isValue = false
      } else if (c == ARRAY_START) {
        append = true
        appendValue = ARRAY_START
      } else if (c == OBJECT_END) {
        if (stackPos > -1) {
          if (syntaxStack(stackPos) == QUOTE && stackPos >= 1) { // isToken
            stackPos -= 1
          }
          if (syntaxStack(stackPos).equals(OBJECT_START)) {
            stackPos -= 1
          } else {
            append = true
            appendValue = OBJECT_START
          }
        } else { // empty
          append = true
          appendValue = OBJECT_END
        }
      } else if (c == ARRAY_END) {
        if (stackPos > -1 && syntaxStack(stackPos).equals(ARRAY_START)) {
          stackPos -= 1
        } else {
          append = true
          appendValue = ARRAY_END
        }
      } else if (c == QUOTE) {
        if (stackPos > -1) {
          if (isValue) {
            pos = skip(
              byteStream,
              pos,
              partition.end,
              c
            )
          } else if (syntaxStack(stackPos).equals(OBJECT_START)) {
            append = true
            appendValue = QUOTE
          } else if (QUOTE == syntaxStack(stackPos)) {
            val _pos = skip(
              byteStream,
              pos,
              partition.end,
              c
            )
            syntaxStack(stackPos) = QUOTE // copy to new string
            syntaxPositions(stackPos) = pos
            pos = _pos
          }
        } else if (isValue) {
          pos = skip(byteStream, pos, partition.end, c)
        }
      } else if (c == SEMI_COLON) {
        isValue = true
      } else if (
        c == COMMA && (stackPos < 0 || !syntaxStack(stackPos).equals(ARRAY_START))
      ) {
        isValue = false
      }

      if (append) {
        if (stackPos < stackPosMax) {
          stackPos += 1
          syntaxStack(stackPos) = appendValue
          syntaxPositions(stackPos) = pos
        } else {
          syntaxStack.append(appendValue)
          syntaxPositions.append(pos)
          stackPos += 1
          stackPosMax = stackPos
        }
        if(appendValue == QUOTE) {
          pos = skip(byteStream, pos, partition.end, QUOTE)
        }
      }

    }

    val finalSyntaxStack = new ArrayBuffer[String]()
    val finalPosStack = new ArrayBuffer[Long]()

    for(i <- 0 to stackPos) {
      val v = syntaxStack(i)
      var p = syntaxPositions(i)
      var appendVal = ""
      v match {
        case OBJECT_START => appendVal = "{"
        case OBJECT_END => appendVal = "}"
        case ARRAY_START => appendVal = "["
        case ARRAY_END => appendVal = "]"
        case QUOTE => {
          bufferedReader = Parser.getBufferedReader(inputStream, options.encoding, p)
          val (token, _pos) = Parser.consume(bufferedReader, options.encoding, p, partition.end, '"')
          bufferedReader.close()
          appendVal = token.substring(1, token.length - 1)
          p = _pos
        }
      }
      finalSyntaxStack.append(appendVal)
      finalPosStack.append(p)
      pos = p
    }

    println("######### getEndState ############")
    println(finalSyntaxStack)
    println(finalPosStack)

    val pastEnd = pos > partition.end

    return (
      partition.path,
      partition.start,
      pos,
      finalSyntaxStack,
      finalPosStack,
      pastEnd
    )

  }

  def partitionLevelSkipping(
      state: Array[String],
      options: JsonOptions
  ): (Int, Int, Int) = {
    var dfa = options.getDFA()

    var level = 0
    var skipLevels = 0
    var dfaState = 0

    // get level before the first rejected or accepted state
    var i = 0;
    var isComplete = false // or accepted
    while (i < state.length && !isComplete) {
      var response = ""
      val elem = state(i)
      if (elem.equals("[")) {
        if (dfa.toNextStateIfArray(level) ||
          dfa.states(dfa.currentState).stateType.equals("descendant")) {
          level += 1
        }
      } else if (elem.equals("{")) {
        level += 1
      } else { // key
        response = dfa.checkToken(elem, level)
      }

      if (response.equals("accept") || response.equals("reject")) {
        isComplete = true
      }
      i += 1

    }

    while (i < state.length) {
      val elem = state(i)
      if (elem.equals("[") || elem.equals("{")) {
        skipLevels += 1
      }
      i += 1
    }

    return (level, skipLevels, dfa.getCurrentState())
  }

  def fullPass(
      options: JsonOptions
  ): Array[InputPartition] = {
    val filePaths: Seq[String] = if (options.filePaths == null) {
      getFilePaths(options)
    } else {
      options.filePaths
    }

    val partitions = getFilePartitions(filePaths, options)

    val sc = SparkContext.getOrCreate()
    val endStates = sc
      .makeRDD(partitions.map(p => (p, p.preferredLocations())))
      .map(partition =>
        getEndState(partition._1.asInstanceOf[JsonInputPartition], options)
      )
      .collect()
    var prevStack = new ArrayBuffer[String]()
    var prevIsString = false
    var prevEnd = 0L
    var prevPath = ""
    var i: Integer = 0

    var partitionInitialStates = ArrayBuffer[
      (
          String, // path
          Long, // start
          Long, // end
          ArrayBuffer[String], // initial state (end state of previous)
          ArrayBuffer[String], // in-state
          ArrayBuffer[Long], // positions of in-state
          Boolean, // isString
          Int, // Level
          Int, // skipLevels
          Int // dfaState
      )
    ]()

    for (p <- endStates) {
      val (path, start, end, syntaxStack, syntaxPositions, isString) =
        p.asInstanceOf[
          (String, Long, Long, ArrayBuffer[String], ArrayBuffer[Long], Boolean)
        ]

      val (level, skipLevels, dfaState) =
        partitionLevelSkipping(prevStack.toArray, options)

      // println("####### " + i + " Start: " + start + " End: " + end)
      // println("Start state: " + prevStack)
      // println("End state: " + syntaxStack)
      // println(level + " " + skipLevels + " " + dfaState + " " + isString)
      if (prevPath != path) {
        prevStack = new ArrayBuffer[String]()
        prevIsString = false
        prevEnd = 0L
        prevPath = ""
      }
      prevPath = path
      val (stack, _syntaxStack, _syntaxPositions) =
        mergeSyntaxStack(prevStack, syntaxStack, syntaxPositions, prevEnd)

      partitionInitialStates.append(
        (
          path,
          prevEnd,
          end,
          prevStack,
          _syntaxStack,
          _syntaxPositions,
          isString,
          level,
          skipLevels,
          dfaState
        )
      )
      prevStack = stack
      prevIsString = isString
      prevEnd = end
      i += 1
    }

    i = partitionInitialStates.length - 1
    var prevStart = 0L
    prevPath = ""
    var finalPartitions: ArrayBuffer[InputPartition] =
      new ArrayBuffer[InputPartition]
    while (i >= 0) {
      val (
        path,
        start,
        end,
        initialState,
        syntaxStack,
        syntaxPositions,
        isString,
        level,
        skipLevels,
        dfaState
      ) =
        partitionInitialStates(i)

      var _skipLevels = skipLevels
      var shiftedStart = start
      var j = i
      while (_skipLevels > 0 && j < partitionInitialStates.length) {
        val (
          path2,
          start2,
          end2,
          _,
          syntaxStack2,
          syntaxPositions2,
          _,
          _,
          _,
          _
        ) =
          partitionInitialStates(j)

        if (path.equals(path2)) {
          var k = 0;
          while (k < syntaxStack2.length && _skipLevels > 0) {
            val c = syntaxStack2(k)
            val pos = syntaxPositions2(k)
            if (c.equals("}") || c.equals("]")) {
              _skipLevels -= 1
              if (_skipLevels == 0) {
                shiftedStart = pos
              }
            }
            k += 1
          }
        } else {
          j = partitionInitialStates.length
        }
        j += 1
      }

      if (shiftedStart < end) {
        val _end = if (prevPath.equals(path)) { prevStart }
        else { end }
        // println(shiftedStart, _end, level, dfaState, initialState)
        val _initialState : Array[Char] = initialState.filter(x => x.size == 1).map(x => x(0).toChar).toArray
        finalPartitions.append(
          (new JsonInputPartition(path, shiftedStart, _end, level, dfaState, _initialState, i))
            .asInstanceOf[InputPartition]
        )

        prevPath = path
        prevStart = shiftedStart
      } else {
        prevPath = ""
      }

      i -= 1
    }

    finalPartitions.toArray.reverse

  }

}

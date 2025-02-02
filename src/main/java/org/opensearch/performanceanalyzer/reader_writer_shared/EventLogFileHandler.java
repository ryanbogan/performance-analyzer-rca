/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

/*
 * Copyright 2019-2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.opensearch.performanceanalyzer.reader_writer_shared;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.performanceanalyzer.PerformanceAnalyzerApp;
import org.opensearch.performanceanalyzer.core.Util;
import org.opensearch.performanceanalyzer.metrics.PerformanceAnalyzerMetrics;
import org.opensearch.performanceanalyzer.rca.framework.metrics.WriterMetrics;
import org.opensearch.performanceanalyzer.reader.EventDispatcher;

public class EventLogFileHandler {
    private static final Logger LOG = LogManager.getLogger(EventLogFileHandler.class);

    private final EventLog eventLog;
    private final String metricsLocation;
    private static final int BUFFER_SIZE = 8192;
    private static final String TMP_FILE_EXT = ".tmp";
    private long lastProcessed;

    public EventLogFileHandler(EventLog eventLog, String metricsLocation) {
        this.eventLog = eventLog;
        this.metricsLocation = metricsLocation;
    }

    public void writeTmpFile(List<Event> dataEntries, long epoch) {
        Util.invokePrivileged(() -> writeTmpFileWithPrivilege(dataEntries, epoch));
    }

    /**
     * This method writes all the metrics corresponding to an epoch to file.
     *
     * <p>The regular case is, create a temporary file with the same path as the actual file but
     * with an extension .tmp. After the .tmp is successfully written, atomically rename it to
     * remove the .tmp.
     *
     * <p>However there are a few corner cases. It can happen that during two successive purges of
     * the Blocking Queue of the Plugin, a few metrics corresponding to an epoch were found. In this
     * case the original file will already exist and an atomic move in the end will overwrite its
     * data. So we copy the file over if it exists, to the .tmp, then we append the .tmp and we
     * finally rename it. This time replacing it is not harmful as the new file has the complete
     * data.
     *
     * <p>If any of the above steps fail, then the tmp file is not deleted from the filesystem. This
     * is fine as the {@link
     * org.opensearch.performanceanalyzer.reader_writer_shared.EventLogFileHandler#deleteFiles},
     * will eventually clean it. The copies are atomic and therefore the reader never reads
     * incompletely written file.
     *
     * @param dataEntries The metrics to be written to file.
     * @param epoch The epoch all the metrics belong to.
     */
    public void writeTmpFileWithPrivilege(List<Event> dataEntries, long epoch) {

        Path path = Paths.get(metricsLocation, String.valueOf(epoch));
        Path tmpPath = Paths.get(path.toString() + TMP_FILE_EXT);

        Event currEntry = null;
        try (OutputStream out =
                Files.newOutputStream(
                        tmpPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (Event event : dataEntries) {
                currEntry = event;
                byte[] data = eventLog.write(event);
                writeInternal(out, data);
            }
        } catch (IOException e) {
            LOG.error(
                    "Error writing entry '{}'. Cause:",
                    currEntry == null ? "NOT_INITIALIZED" : currEntry.key,
                    e);
        }
    }

    public void renameFromTmp(long epoch) {
        Util.invokePrivileged(() -> renameFromTmpWithPrivilege(epoch));
    }

    public void renameFromTmpWithPrivilege(long epoch) {
        Path path = Paths.get(metricsLocation, String.valueOf(epoch));
        Path tmpPath = Paths.get(path.toString() + TMP_FILE_EXT);
        File tempFile = new File(tmpPath.toString());
        // Only if the tmp file is present, we want to create the writer file.
        // If not, we will publish a metric.
        if (tempFile.exists()) {
            // This is done only when no exception is thrown.
            try {
                Files.move(tmpPath, path, REPLACE_EXISTING, ATOMIC_MOVE);
            } catch (IOException e) {
                LOG.error("Error moving file {} to {}.", tmpPath.toString(), path.toString(), e);
            }
        } else {
            PerformanceAnalyzerApp.WRITER_METRICS_AGGREGATOR.updateStat(
                    WriterMetrics.WRITER_FILE_CREATION_SKIPPED, "", 1);
        }
    }

    public void read(long timestamp, EventDispatcher processor) {
        if (timestamp <= lastProcessed) {
            return;
        }

        String filename = String.valueOf(timestamp);
        Path pathToFile = Paths.get(metricsLocation, filename);
        File tempFile = new File(pathToFile.toString());
        if (!tempFile.exists()) {
            long mCurrT = System.currentTimeMillis();
            LOG.debug("Didnt find {} at {}", filename, mCurrT);
            return;
        }
        readInternal(pathToFile, BUFFER_SIZE, processor);
        lastProcessed = timestamp;
        // LOG.info("PARSED - {} {}", filename, ret);
        eventLog.clear();
    }

    private void writeInternal(OutputStream stream, byte[] data) throws IOException {
        int len = data.length;
        int rem = len;
        while (rem > 0) {
            int n = Math.min(rem, BUFFER_SIZE);
            stream.write(data, (len - rem), n);
            rem -= n;
        }
    }

    private void readInternal(Path pathToFile, int bufferSize, EventDispatcher processor) {
        try (SeekableByteChannel channel =
                Files.newByteChannel(pathToFile, StandardOpenOption.READ)) {
            ByteBuffer byteBuffer = ByteBuffer.allocate(bufferSize);

            while (channel.read(byteBuffer) > 0) {
                ((Buffer) byteBuffer).flip();
                // LOG.info(" MAP {}", byteBuffer);
                eventLog.read(byteBuffer, processor);
                ((Buffer) byteBuffer).clear();
                // TODO: Handle edge case where buffer is too small.
            }
        } catch (IOException ex) {
            LOG.error("Error reading file", ex);
        }
    }

    public void deleteAllFiles() {
        Util.invokePrivileged(this::deleteAllFilesWithPrivilege);
    }

    public void deleteAllFilesWithPrivilege() {
        LOG.debug("Cleaning up any leftover files in [{}]", metricsLocation);
        File root = new File(metricsLocation);
        String[] filesToDelete = root.list();
        if (filesToDelete == null) {
            return;
        }
        deleteFiles(Arrays.asList(filesToDelete));
    }

    public void deleteFiles(List<String> filesToDelete) {
        LOG.debug("Starting to delete old writer files");
        long startTime = System.currentTimeMillis();

        if (filesToDelete == null) {
            return;
        }
        int filesDeletedCount = 0;
        File root = new File(metricsLocation);
        for (String fileToDelete : filesToDelete) {
            File file = new File(root, fileToDelete);
            removeFilesWithPrivilege(file);
            filesDeletedCount += 1;
        }
        long duration = System.currentTimeMillis() - startTime;
        PerformanceAnalyzerApp.WRITER_METRICS_AGGREGATOR.updateStat(
                WriterMetrics.EVENT_LOG_FILES_DELETION_TIME, "", duration);
        PerformanceAnalyzerApp.WRITER_METRICS_AGGREGATOR.updateStat(
                WriterMetrics.EVENT_LOG_FILES_DELETED, "", filesDeletedCount);
        LOG.debug("'{}' Old writer files cleaned up.", filesDeletedCount);
    }

    public void removeFilesWithPrivilege(File file) {
        Util.invokePrivileged(() -> PerformanceAnalyzerMetrics.removeMetrics(file));
    }
}

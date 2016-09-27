package org.easybatch.core.job;

import org.easybatch.core.listener.*;
import org.easybatch.core.processor.CompositeRecordProcessor;
import org.easybatch.core.processor.RecordProcessor;
import org.easybatch.core.reader.RecordReader;
import org.easybatch.core.record.Batch;
import org.easybatch.core.record.Record;
import org.easybatch.core.writer.RecordWriter;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import static org.easybatch.core.util.Utils.formatErrorThreshold;

/**
 * Implementation of read-process-write job pattern.
 *
 * <strong>Each instance of this class corresponds to a job run (ie execution). Running the same instance twice will throw an {@code IllegalStateException}.</strong>
 *
 * @author Mahmoud Ben Hassine (mahmoud.benhassine@icloud.com)
 */
class BatchJob implements Job {

    private static final Logger LOGGER = Logger.getLogger(BatchJob.class.getName());
    private static final String DEFAULT_JOB_NAME = "job";

    private String name;

    private RecordReader recordReader;
    private RecordWriter recordWriter;
    private RecordProcessor recordProcessor;

    private JobListener jobListener;
    private BatchListener batchListener;
    private RecordReaderListener recordReaderListener;
    private RecordWriterListener recordWriterListener;
    private PipelineListener pipelineListener;

    private JobParameters parameters;
    private JobMetrics metrics;
    private JobReport report;
    private JobMonitor monitor;

    static {
        try {
            if (System.getProperty("java.util.logging.config.file") == null &&
                    System.getProperty("java.util.logging.config.class") == null) {
                LogManager.getLogManager().readConfiguration(BatchJob.class.getResourceAsStream("/logging.properties"));
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to load logging configuration file", e);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    BatchJob(JobParameters parameters) {
        this.parameters = parameters;
        this.name = DEFAULT_JOB_NAME;
        metrics = new JobMetrics();
        report = new JobReport();
        report.setParameters(parameters);
        report.setMetrics(metrics);
        report.setJobName(name);
        monitor = new JobMonitor(report);
        recordReader = new NoOpRecordReader();
        recordProcessor = new CompositeRecordProcessor();
        recordWriter = new NoOpRecordWriter();
        recordReaderListener = new CompositeRecordReaderListener();
        pipelineListener = new CompositePipelineListener();
        recordWriterListener = new CompositeRecordWriterListener();
        batchListener = new CompositeBatchListener();
        jobListener = new CompositeJobListener();
    }

    @Override
    public JobReport call() {
        jobListener.beforeJobStart(parameters);

        report.setStatus(JobStatus.STARTING);
        metrics.setStartTime(System.currentTimeMillis());
        LOGGER.log(Level.INFO, "Starting job ''{0}''", name);
        LOGGER.log(Level.INFO, "Batch size: {0}", parameters.getBatchSize());
        LOGGER.log(Level.INFO, "Error threshold: {0}", formatErrorThreshold(parameters.getErrorThreshold()));
        LOGGER.log(Level.INFO, "Jmx monitoring: {0}", parameters.isJmxMonitoring());
        if (parameters.isJmxMonitoring()) {
            monitor.registerJmxMBeanFor(this);
        }

        try {

            /*
             * Open reader
             */
            try {
                recordReader.open();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unable to open record reader", e);
                report.setStatus(JobStatus.FAILED);
                report.setLastError(e);
                metrics.setEndTime(System.currentTimeMillis());
                LOGGER.log(Level.INFO, "Job ''{0}'' finished with status: {1}", new Object[]{name, report.getStatus()});
                if (parameters.isJmxMonitoring()) {
                    monitor.notifyJobReportUpdate();
                }
                jobListener.afterJobEnd(report);
                return report;
            }

            /*
             * Open writer
             */
            try {
                recordWriter.open();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unable to open record writer", e);
                report.setStatus(JobStatus.FAILED);
                report.setLastError(e);
                metrics.setEndTime(System.currentTimeMillis());
                LOGGER.log(Level.INFO, "Job ''{0}'' finished with status: {1}", new Object[]{name, report.getStatus()});
                if (parameters.isJmxMonitoring()) {
                    monitor.notifyJobReportUpdate();
                }
                jobListener.afterJobEnd(report);
                return report;
            }

            LOGGER.log(Level.INFO, "Job ''{0}'' started", name);
            report.setStatus(JobStatus.STARTED);
            boolean moreRecords = true;

            while (moreRecords) {

                /*
                 * Begin batch processing
                 */
                batchListener.beforeBatchReading();

                Batch batch = new Batch();
                for (int i = 0; i < parameters.getBatchSize(); i++) {

                    /*
                     * Read record
                     */
                    Record record;
                    try {
                        recordReaderListener.beforeRecordReading();
                        record = recordReader.readRecord();
                        recordReaderListener.afterRecordReading(record);
                        if (record == null) {
                            moreRecords = false;
                            break;
                        } else {
                            metrics.incrementReadCount();
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Unable to read next record", e);
                        recordReaderListener.onRecordReadingException(e);
                        report.setStatus(JobStatus.FAILED);
                        report.setLastError(e);
                        metrics.setEndTime(System.currentTimeMillis());
                        LOGGER.log(Level.INFO, "Job ''{0}'' finished with status: {1}", new Object[]{name, report.getStatus()});
                        if (parameters.isJmxMonitoring()) {
                            monitor.notifyJobReportUpdate();
                        }
                        jobListener.afterJobEnd(report);
                        return report;
                    }

                    /*
                     * Process record
                     */
                    Record processedRecord;
                    try {
                        pipelineListener.beforeRecordProcessing(record);
                        if (parameters.isJmxMonitoring()) {
                            monitor.notifyJobReportUpdate();
                        }
                        processedRecord = recordProcessor.processRecord(record);
                        pipelineListener.afterRecordProcessing(record, processedRecord);
                        if (processedRecord == null) {
                            LOGGER.log(Level.INFO, "{0} has been filtered", record);
                            metrics.incrementFilteredCount();
                        } else {
                            batch.addRecord(processedRecord);
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Unable to process " + record, e);
                        pipelineListener.onRecordProcessingException(record, e);
                        metrics.incrementErrorCount();
                        report.setLastError(e);
                        if (metrics.getErrorCount() > parameters.getErrorThreshold()) {
                            LOGGER.log(Level.SEVERE, "Error threshold exceeded. Aborting execution");
                            report.setStatus(JobStatus.FAILED);
                            metrics.setEndTime(System.currentTimeMillis());
                            LOGGER.log(Level.INFO, "Job ''{0}'' finished with status: {1}", new Object[]{name, report.getStatus()});
                            if (parameters.isJmxMonitoring()) {
                                monitor.notifyJobReportUpdate();
                            }
                            jobListener.afterJobEnd(report);
                            return report;
                        }
                    }

                }

                batchListener.afterBatchProcessing(batch);

                /*
                 * Write records
                 */
                try {
                    if (!batch.isEmpty()) {
                        recordWriterListener.beforeRecordWriting(batch);
                        recordWriter.writeRecords(batch);
                        recordWriterListener.afterRecordWriting(batch);
                        batchListener.afterBatchWriting(batch);
                        metrics.incrementWriteCount(batch.size());
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Unable to write records", e);
                    recordWriterListener.onRecordWritingException(batch, e);
                    batchListener.onBatchWritingException(batch, e);
                    report.setStatus(JobStatus.FAILED);
                    report.setLastError(e);
                    metrics.setEndTime(System.currentTimeMillis());
                    LOGGER.log(Level.INFO, "Job ''{0}'' finished with status: {1}", new Object[]{name, report.getStatus()});
                    if (parameters.isJmxMonitoring()) {
                        monitor.notifyJobReportUpdate();
                    }
                    jobListener.afterJobEnd(report);
                    return report;
                }

                /*
                 * End batch processing
                 */
            }
            report.setStatus(JobStatus.STOPPING);
        } finally {
            try {
                recordReader.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Unable to close record reader", e);
                report.setLastError(e);
            }
            try {
                recordWriter.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Unable to close record writer", e);
                report.setLastError(e);
            }
        }

        report.setStatus(JobStatus.COMPLETED);
        metrics.setEndTime(System.currentTimeMillis());
        LOGGER.log(Level.INFO, "Job ''{0}'' finished with status: {1}", new Object[]{name, report.getStatus()});
        if (parameters.isJmxMonitoring()) {
            monitor.notifyJobReportUpdate();
        }
        jobListener.afterJobEnd(report);
        return report;
    }

    /*
     * Setters for job components
     */

    public void setRecordReader(RecordReader recordReader) {
        this.recordReader = recordReader;
    }

    public void setRecordWriter(RecordWriter recordWriter) {
        this.recordWriter = recordWriter;
    }

    public void addRecordProcessor(RecordProcessor recordProcessor) {
        ((CompositeRecordProcessor) this.recordProcessor).addRecordProcessor(recordProcessor);
    }

    public void addBatchListener(BatchListener batchListener) {
        ((CompositeBatchListener) this.batchListener).addBatchListener(batchListener);
    }

    public void addJobListener(JobListener jobListener) {
        ((CompositeJobListener) this.jobListener).addJobListener(jobListener);
    }

    public void addRecordReaderListener(RecordReaderListener recordReaderListener) {
        ((CompositeRecordReaderListener) this.recordReaderListener).addRecordReaderListener(recordReaderListener);
    }

    public void addRecordWriterListener(RecordWriterListener recordWriterListener) {
        ((CompositeRecordWriterListener) this.recordWriterListener).addRecordWriterListener(recordWriterListener);
    }

    public void addPipelineListener(PipelineListener pipelineListener) {
        ((CompositePipelineListener) this.pipelineListener).addPipelineListener(pipelineListener);
    }

    public void setName(String name) {
        this.name = name;
        this.report.setJobName(name);
    }
}
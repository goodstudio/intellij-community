// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.internal.statistic.eventLog.connection;

import com.intellij.internal.statistic.config.EventLogOptions;
import com.intellij.internal.statistic.config.eventLog.EventLogBuildType;
import com.intellij.internal.statistic.eventLog.*;
import com.intellij.internal.statistic.eventLog.connection.StatisticsResult.ResultCode;
import com.intellij.internal.statistic.eventLog.connection.request.StatsHttpRequests;
import com.intellij.internal.statistic.eventLog.connection.request.StatsHttpResponse;
import com.intellij.internal.statistic.eventLog.connection.request.StatsRequestBuilder;
import com.intellij.internal.statistic.eventLog.filters.LogEventFilter;
import com.jetbrains.fus.reporting.model.http.StatsConnectionSettings;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.intellij.internal.statistic.config.StatisticsStringUtil.isEmpty;

@ApiStatus.Internal
public class EventLogStatisticsService implements StatisticsService {

  private final EventLogSendConfig myConfiguration;
  private final EventLogSettingsClient mySettingsClient;

  private final EventLogSendListener mySendListener;

  public EventLogStatisticsService(@NotNull EventLogSendConfig config,
                                   @NotNull EventLogApplicationInfo application,
                                   @Nullable EventLogSendListener listener) {
    myConfiguration = config;
    mySettingsClient = new EventLogUploadSettingsClient(config.getRecorderId(), application, TimeUnit.MINUTES.toMillis(10));
    mySendListener = listener;
  }

  @TestOnly
  public EventLogStatisticsService(@NotNull EventLogSendConfig config,
                                   @Nullable EventLogSendListener listener,
                                   @Nullable EventLogSettingsClient settingsClient) {
    myConfiguration = config;
    mySettingsClient = settingsClient;
    mySendListener = listener;
  }

  @Override
  public StatisticsResult send() {
    return send(myConfiguration, mySettingsClient, new EventLogCounterResultDecorator(mySendListener));
  }

  public StatisticsResult send(@NotNull EventLogResultDecorator decorator) {
    return send(myConfiguration, mySettingsClient, decorator);
  }

  public static StatisticsResult send(@NotNull EventLogSendConfig config,
                                      @NotNull EventLogSettingsClient settings,
                                      @NotNull EventLogResultDecorator decorator) {
    final EventLogApplicationInfo info = settings.getApplicationInfo();
    final DataCollectorDebugLogger logger = info.getLogger();

    final List<EventLogFile> logs = getLogFiles(config, logger);
    if (!config.isSendEnabled()) {
      cleanupEventLogFiles(logs, logger);
      return new StatisticsResult(ResultCode.NOTHING_TO_SEND, "Event Log collector is not enabled");
    }

    if (logs.isEmpty()) {
      return new StatisticsResult(ResultCode.NOTHING_TO_SEND, "No files to send");
    }

    if (!settings.isConfigurationReachable()) {
      return new StatisticsResult(StatisticsResult.ResultCode.ERROR_IN_CONFIG, "ERROR: settings server is unreachable");
    }

    if (!settings.isSendEnabled()) {
      cleanupEventLogFiles(logs, logger);
      return new StatisticsResult(StatisticsResult.ResultCode.NOT_PERMITTED_SERVER, "NOT_PERMITTED");
    }

    final String serviceUrl = settings.provideServiceUrl();
    if (serviceUrl == null) {
      return new StatisticsResult(StatisticsResult.ResultCode.ERROR_IN_CONFIG, "ERROR: unknown Statistics Service URL.");
    }

    final boolean isInternal = info.isInternal();
    final String productCode = info.getProductCode();
    EventLogBuildType defaultBuildType = getDefaultBuildType(info.isEAP());
    LogEventFilter baseFilter = settings.provideBaseEventFilter(info.getBaselineVersion());

    MachineId machineId = getActualOrDisabledMachineId(config.getMachineId(), settings);
    try {
      StatsConnectionSettings connectionSettings = info.getConnectionSettings();

      decorator.onLogsLoaded(logs.size());
      final List<File> toRemove = new ArrayList<>(logs.size());
      for (EventLogFile logFile : logs) {
        File file = logFile.getFile();
        EventLogBuildType type = logFile.getType(defaultBuildType);
        LogEventFilter filter = settings.provideEventFilter(baseFilter, type);
        String deviceId = config.getDeviceId();
        LogEventRecordRequest recordRequest =
          LogEventRecordRequest.Companion.create(file, config.getRecorderId(), productCode, deviceId, filter, isInternal, logger,
                                                 machineId, config.isEscapingEnabled());
        ValidationErrorInfo error = validate(recordRequest, file);
        if (error != null) {
          if (logger.isTraceEnabled()) {
            logger.trace("Statistics. " + file.getName() + "-> " + error.getMessage());
          }
          decorator.onFailed(recordRequest, error.getCode(), null);
          toRemove.add(file);
          continue;
        }

        try {
          logger.info("Statistics. Starting sending " + file.getName() + " to " + serviceUrl);
          StatsHttpRequests.post(serviceUrl, connectionSettings).
            withBody(LogEventSerializer.INSTANCE.toString(recordRequest), "application/json", StandardCharsets.UTF_8).
            succeed((r, code) -> {
              toRemove.add(file);
              decorator.onSucceed(recordRequest, loadAndLogResponse(logger, r, file), file.getAbsolutePath());
            }).
            fail((r, code) -> {
              if (code == HttpURLConnection.HTTP_BAD_REQUEST) {
                toRemove.add(file);
              }
              decorator.onFailed(recordRequest, code, loadAndLogResponse(logger, r, file));
            }).send();
        }
        catch (Exception e) {
          if (logger.isTraceEnabled()) {
            logger.trace("Statistics. " + file.getName() + " -> " + e.getMessage());
          }
          //noinspection InstanceofCatchParameter
          int errorCode = e instanceof StatsRequestBuilder.InvalidHttpRequest ? ((StatsRequestBuilder.InvalidHttpRequest)e).getCode() : 50;
          decorator.onFailed(null, errorCode, null);
        }
      }

      cleanupFiles(toRemove, logger);
      return decorator.onFinished();
    }
    catch (Exception e) {
      final String message = e.getMessage();
      logger.info(message != null ? message : "", e);
      throw new StatServiceException("Statistics. Error during data sending.", e);
    }
  }

  private static MachineId getActualOrDisabledMachineId(@NotNull MachineId machineId, @NotNull EventLogSettingsClient settings) {
    if (machineId == MachineId.DISABLED) {
      return MachineId.DISABLED;
    }
    Map<String, String> options = settings.provideOptions();
    String machineIdSaltOption = options.get(EventLogOptions.MACHINE_ID_SALT);
    if (EventLogOptions.MACHINE_ID_DISABLED.equals(machineIdSaltOption)) {
      return MachineId.DISABLED;
    }
    return machineId;
  }

  private static @NotNull EventLogBuildType getDefaultBuildType(boolean isEap) {
    return isEap ? EventLogBuildType.EAP : EventLogBuildType.RELEASE;
  }

  private static @NotNull String loadAndLogResponse(@NotNull DataCollectorDebugLogger logger,
                                                    @NotNull StatsHttpResponse response,
                                                    @NotNull File file) throws IOException {
    String message = response.readAsString();
    String content = message != null ? message : Integer.toString(response.getStatusCode());

    if (logger.isTraceEnabled()) {
      logger.trace(file.getName() + " -> " + content);
    }
    return content;
  }

  private static @Nullable ValidationErrorInfo validate(@Nullable LogEventRecordRequest request, @NotNull File file) {
    if (request == null) {
      return new ValidationErrorInfo("File is empty or has invalid format: " + file.getName(), 1);
    }

    if (isEmpty(request.getDevice())) {
      return new ValidationErrorInfo("Cannot upload event log, device ID is empty", 2);
    }
    else if (isEmpty(request.getProduct())) {
      return new ValidationErrorInfo("Cannot upload event log, product code is empty", 3);
    }
    else if (isEmpty(request.getRecorder())) {
      return new ValidationErrorInfo("Cannot upload event log, recorder code is empty", 4);
    }
    else if (request.getRecords().isEmpty()) {
      return new ValidationErrorInfo("Cannot upload event log, record list is empty", 5);
    }

    for (LogEventRecord content : request.getRecords()) {
      if (content.getEvents().isEmpty()) {
        return new ValidationErrorInfo("Cannot upload event log, event list is empty", 6);
      }
    }
    return null;
  }

  protected static @NotNull List<EventLogFile> getLogFiles(@NotNull EventLogSendConfig config, @NotNull DataCollectorDebugLogger logger) {
    try {
      return config.getFilesToSendProvider().getFilesToSend();
    }
    catch (Exception e) {
      final String message = e.getMessage();
      logger.info(message != null ? message : "", e);
    }
    return Collections.emptyList();
  }

  private static void cleanupEventLogFiles(@NotNull List<EventLogFile> toRemove, @NotNull DataCollectorDebugLogger logger) {
    List<File> filesToRemove = new ArrayList<>();
    for (EventLogFile file : toRemove) {
      filesToRemove.add(file.getFile());
    }
    cleanupFiles(filesToRemove, logger);
  }

  private static void cleanupFiles(@NotNull List<? extends File> toRemove, @NotNull DataCollectorDebugLogger logger) {
    for (File file : toRemove) {
      if (!file.delete()) {
        logger.warn("Failed deleting event log: " + file.getName());
      }

      if (logger.isTraceEnabled()) {
        logger.trace("Removed sent log: " + file.getName());
      }
    }
  }

  private static final class ValidationErrorInfo {
    private final int myCode;
    private final String myError;

    private ValidationErrorInfo(@NotNull String error, int code) {
      myError = error;
      myCode = code;
    }

    private int getCode() {
      return myCode;
    }

    private @NotNull String getMessage() {
      return myError;
    }
  }

  private static final class EventLogCounterResultDecorator implements EventLogResultDecorator {
    private final EventLogSendListener myListener;

    private int myLocalFiles = -1;
    private final List<String> mySuccessfullySentFiles = new ArrayList<>();
    private final List<Integer> myErrors = new ArrayList<>();

    private EventLogCounterResultDecorator(@Nullable EventLogSendListener listener) {
      myListener = listener;
    }

    @Override
    public void onLogsLoaded(int localFiles) {
      myLocalFiles = localFiles;
    }

    @Override
    public void onSucceed(@NotNull LogEventRecordRequest request, @NotNull String content, @NotNull String logPath) {
      mySuccessfullySentFiles.add(logPath);
    }

    @Override
    public void onFailed(@Nullable LogEventRecordRequest request, int error, @Nullable String content) {
      myErrors.add(error);
    }

    @Override
    public @NotNull StatisticsResult onFinished() {
      if (myListener != null) {
        myListener.onLogsSend(mySuccessfullySentFiles, myErrors, myLocalFiles);
      }

      int succeed = mySuccessfullySentFiles.size();
      int failed = myErrors.size();
      int total = succeed + failed;
      if (total == 0) {
        return new StatisticsResult(ResultCode.NOTHING_TO_SEND, "No files to upload.");
      }
      else if (failed > 0) {
        return new StatisticsResult(ResultCode.SENT_WITH_ERRORS, "Uploaded " + succeed + " out of " + total + " files.");
      }
      return new StatisticsResult(ResultCode.SEND, "Uploaded " + succeed + " files.");
    }
  }
}

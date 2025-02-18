// Copyright (c) 2020, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package com.oracle.weblogic.imagetool.builder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.oracle.weblogic.imagetool.logging.LoggingFacade;
import com.oracle.weblogic.imagetool.logging.LoggingFactory;
import com.oracle.weblogic.imagetool.util.CloseableList;
import com.oracle.weblogic.imagetool.util.Utils;

public class BuildCommand {
    private static final LoggingFacade logger = LoggingFactory.getLogger(BuildCommand.class);

    private final List<String> command;
    private final List<BuildArg> buildArgs;
    private final String context;

    /**
     * Create a build command for creating an image.  At some point, it might
     * be beneficial to subclass this with separate classes for each builder (docker or podman).
     * For now, the differences do not justify the extra complexity.
     */
    public BuildCommand(String buildEngine, String contextFolder) {
        Objects.requireNonNull(contextFolder);
        buildArgs = new ArrayList<>();
        command = Stream.of(buildEngine, "build", "--no-cache").collect(Collectors.toList());
        context = contextFolder;
    }

    /**
     * Add Docker image tag name for this build command.
     * @param value name to be used as the image tag.
     * @return this
     */
    public BuildCommand tag(String value) {
        if (Utils.isEmptyString(value)) {
            return this;
        }
        command.add("--tag");
        command.add(value);
        return this;
    }

    /**
     * Always remove intermediate containers if set to true.
     * By default, Docker leaves intermediate containers when the build fails which is not ideal for CI/CD servers.
     * @param value true to enable --force-rm on docker build.
     * @return this
     */
    public BuildCommand forceRm(boolean value) {
        if (value) {
            command.add("--force-rm");
        }
        return this;
    }

    /**
     * Add a --build-arg to the Docker build command.
     * Conceal is defaulted to false.
     * @param key the ARG
     * @param value the value to be used in the Dockerfile for this ARG
     */
    public BuildCommand buildArg(String key, String value) {
        return buildArg(key, value, false);
    }

    /**
     * Add a --build-arg to the Docker build command.
     * @param key the ARG
     * @param value the value to be used in the Dockerfile for this ARG
     * @param conceal true for passwords so the value is not logged
     */
    public BuildCommand buildArg(String key, String value, boolean conceal) {
        if (Utils.isEmptyString(value)) {
            return this;
        }
        BuildArg arg = new BuildArg();
        arg.key = key;
        arg.value = value;
        arg.conceal = conceal;
        buildArgs.add(arg);
        return this;
    }

    /**
     * Add a --network to the Docker build command.
     * @param value the Docker network to use
     */
    public BuildCommand network(String value) {
        if (Utils.isEmptyString(value)) {
            return this;
        }
        command.add("--network");
        command.add(value);
        return this;
    }

    /**
     * Add a --pull to the Docker build command.  If value is false, return without adding the --pull.
     * @param value true to add the pull
     */
    public BuildCommand pull(boolean value) {
        if (value) {
            command.add("--pull");
        }
        return this;
    }

    /**
     * Executes the given docker command and writes the process stdout to log.
     *
     * @param dockerLog      log file to write to
     * @throws IOException          if an error occurs reading from the process inputstream.
     * @throws InterruptedException when the process wait is interrupted.
     */
    public void run(Path dockerLog)
        throws IOException, InterruptedException {
        // process builder
        logger.entering(getCommand(false), dockerLog);
        Path dockerLogPath = createFile(dockerLog);
        logger.finer("Docker log: {0}", dockerLogPath);
        List<OutputStream> outputStreams = new ArrayList<>();

        outputStreams.add(System.out);

        if (dockerLogPath != null) {
            logger.info("dockerLog: " + dockerLog);
            outputStreams.add(Files.newOutputStream(dockerLogPath));
        }

        ProcessBuilder processBuilder = new ProcessBuilder(getCommand(true));
        logger.finer("Starting docker process...");
        final Process process = processBuilder.start();
        logger.finer("Docker process started");
        writeFromInputToOutputStreams(process.getInputStream(), outputStreams);
        logger.finer("Waiting for Docker to finish");
        if (process.waitFor() != 0) {
            Utils.processError(process);
        }
    }

    /**
     * Create a file with the given path.
     *
     * @param filePath        the path of the file to create
     * @return file path or null in case of error
     */
    private Path createFile(Path filePath) {
        Path logFilePath = filePath;
        if (logFilePath != null) {
            try {
                if (!Files.exists(logFilePath)) {
                    Files.createDirectories(logFilePath.getParent());
                    Files.createFile(logFilePath);
                } else {
                    if (Files.isDirectory(logFilePath)) {
                        logFilePath = Paths.get(logFilePath.toAbsolutePath().toString(), "dockerbuild.log");
                        if (Files.exists(logFilePath)) {
                            Files.delete(logFilePath);
                        }
                        Files.createFile(logFilePath);
                    }
                }
            } catch (IOException e) {
                logger.fine("Failed to create log file for the build command", e);
                logFilePath = null;
            }
        }
        return logFilePath;
    }

    private void writeFromInputToOutputStreams(InputStream inputStream, List<OutputStream> outputStreams) {
        Thread readerThread = new Thread(() -> {
            try (
                BufferedReader processReader = new BufferedReader(new InputStreamReader(inputStream));
                CloseableList<PrintWriter> printWriters = createPrintWriters(outputStreams)
            ) {
                if (!printWriters.isEmpty()) {
                    String line;
                    while ((line = processReader.readLine()) != null) {
                        String finalLine = line;
                        printWriters.forEach(x -> x.println(finalLine));
                    }
                }
            } catch (IOException e) {
                logger.severe(e.getMessage());
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private CloseableList<PrintWriter> createPrintWriters(List<OutputStream> outputStreams) {
        CloseableList<PrintWriter> retVal = new CloseableList<>();
        for (OutputStream outputStream : outputStreams) {
            retVal.add(new PrintWriter(new OutputStreamWriter(outputStream), true));
        }
        return retVal;
    }

    private List<String> getCommand(boolean showPasswords) {
        List<String> result = new ArrayList<>(command);
        for (BuildArg arg : buildArgs) {
            result.addAll(arg.toList(showPasswords));
        }
        result.add(context);
        return result;
    }

    @Override
    public String toString() {
        return String.join(" ", getCommand(false));
    }

    private static class BuildArg {
        String key;
        String value;
        boolean conceal;

        List<String> toList(boolean reveal) {
            List<String> result = new ArrayList<>();
            result.add("--build-arg");
            if (conceal && !reveal) {
                result.add(key + "=" + "********");
            } else {
                result.add(key + "=" + value);
            }
            return result;
        }
    }
}

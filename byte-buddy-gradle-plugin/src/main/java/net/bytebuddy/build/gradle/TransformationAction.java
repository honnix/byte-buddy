/*
 * Copyright 2014 - 2020 Rafael Winterhalter
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
package net.bytebuddy.build.gradle;

import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.build.BuildLogger;
import net.bytebuddy.build.EntryPoint;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.compile.AbstractCompile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies a transformation to the classes that were generated by a compilation task.
 */
public class TransformationAction implements Action<Task> {

    /**
     * The current project.
     */
    private final Project project;

    /**
     * The current project's Byte Buddy extension.
     */
    private final ByteBuddyExtension byteBuddyExtension;

    /**
     * The task to which this transformation was appended.
     */
    private final AbstractCompile task;

    /**
     * Creates a new transformation action.
     *
     * @param project   The current project.
     * @param extension The current project's Byte Buddy extension.
     * @param task      The task to which this transformation was appended.
     */
    public TransformationAction(Project project, ByteBuddyExtension extension, AbstractCompile task) {
        this.project = project;
        this.byteBuddyExtension = extension;
        this.task = task;
    }

    /**
     * {@inheritDoc}
     */
    public void execute(Task task) {
        try {
            apply(this.task.getDestinationDir(), this.task.getClasspath());
        } catch (IOException exception) {
            throw new GradleException("Error accessing file system", exception);
        }
    }

    /**
     * Applies the instrumentation.
     *
     * @param root      The root folder that contains all class files.
     * @param classPath An iterable over all class path elements.
     * @throws IOException If an I/O exception occurs.
     */
    @SuppressWarnings("unchecked")
    private void apply(File root, Iterable<? extends File> classPath) throws IOException {
        if (!root.isDirectory()) {
            throw new GradleException("Not a directory: " + root);
        }
        ClassLoaderResolver classLoaderResolver = new ClassLoaderResolver();
        try {
            List<Plugin.Factory> factories = new ArrayList<Plugin.Factory>(byteBuddyExtension.getTransformations().size());
            for (Transformation transformation : byteBuddyExtension.getTransformations()) {
                String plugin = transformation.getPlugin();
                try {
                    factories.add(new Plugin.Factory.UsingReflection((Class<? extends Plugin>) Class.forName(plugin,
                            false,
                            classLoaderResolver.resolve(transformation.getClassPath(root, classPath))))
                            .with(transformation.makeArgumentResolvers())
                            .with(Plugin.Factory.UsingReflection.ArgumentResolver.ForType.of(File.class, root),
                                    Plugin.Factory.UsingReflection.ArgumentResolver.ForType.of(Logger.class, project.getLogger()),
                                    Plugin.Factory.UsingReflection.ArgumentResolver.ForType.of(BuildLogger.class, new GradleBuildLogger(project.getLogger()))));
                    project.getLogger().info("Resolved plugin: {}", transformation.getRawPlugin());
                } catch (Throwable throwable) {
                    throw new GradleException("Cannot resolve plugin: " + transformation.getRawPlugin(), throwable);
                }
            }
            EntryPoint entryPoint = byteBuddyExtension.getInitialization().getEntryPoint(classLoaderResolver, root, classPath);
            project.getLogger().info("Resolved entry point: {}", entryPoint);
            List<ClassFileLocator> classFileLocators = new ArrayList<ClassFileLocator>();
            for (File artifact : classPath) {
                classFileLocators.add(artifact.isFile()
                        ? ClassFileLocator.ForJarFile.of(artifact)
                        : new ClassFileLocator.ForFolder(artifact));
            }
            ClassFileLocator classFileLocator = new ClassFileLocator.Compound(classFileLocators);
            Plugin.Engine.Summary summary;
            try {
                project.getLogger().info("Processing class files located in in: {}", root);
                Plugin.Engine pluginEngine;
                try {
                    ClassFileVersion classFileVersion;
                    JavaPluginConvention convention = (JavaPluginConvention) project.getConvention().getPlugins().get("java");
                    if (convention == null) {
                        classFileVersion = ClassFileVersion.ofThisVm();
                        project.getLogger().warn("Could not locate Java target version, build is JDK dependant: {}", classFileVersion.getMajorVersion());
                    } else {
                        classFileVersion = ClassFileVersion.ofJavaVersion(Integer.parseInt(convention.getTargetCompatibility().getMajorVersion()));
                        project.getLogger().debug("Java version detected: {}", convention.getTargetCompatibility().getMajorVersion());
                    }
                    pluginEngine = Plugin.Engine.Default.of(entryPoint, classFileVersion, byteBuddyExtension.getMethodNameTransformer());
                } catch (Throwable throwable) {
                    throw new GradleException("Cannot create plugin engine", throwable);
                }
                try {
                    summary = pluginEngine
                            .with(byteBuddyExtension.isExtendedParsing()
                                    ? Plugin.Engine.PoolStrategy.Default.EXTENDED
                                    : Plugin.Engine.PoolStrategy.Default.FAST)
                            .with(classFileLocator)
                            .with(new TransformationLogger(project.getLogger()))
                            .withErrorHandlers(Plugin.Engine.ErrorHandler.Enforcing.ALL_TYPES_RESOLVED, byteBuddyExtension.isFailOnLiveInitializer()
                                    ? Plugin.Engine.ErrorHandler.Enforcing.NO_LIVE_INITIALIZERS
                                    : Plugin.Engine.Listener.NoOp.INSTANCE, byteBuddyExtension.isFailFast()
                                    ? Plugin.Engine.ErrorHandler.Failing.FAIL_FAST
                                    : Plugin.Engine.Listener.NoOp.INSTANCE)
                            .with(byteBuddyExtension.getThreads() == 0
                                    ? Plugin.Engine.Dispatcher.ForSerialTransformation.Factory.INSTANCE
                                    : new Plugin.Engine.Dispatcher.ForParallelTransformation.WithThrowawayExecutorService.Factory(byteBuddyExtension.getThreads()))
                            .apply(new Plugin.Engine.Source.ForFolder(root), new Plugin.Engine.Target.ForFolder(root), factories);
                } catch (Throwable throwable) {
                    throw new GradleException("Failed to transform class files in " + root, throwable);
                }
            } finally {
                classFileLocator.close();
            }
            if (!summary.getFailed().isEmpty()) {
                throw new GradleException(summary.getFailed() + " type transformations have failed");
            } else if (byteBuddyExtension.isWarnOnEmptyTypeSet() && summary.getTransformed().isEmpty()) {
                project.getLogger().warn("No types were transformed during plugin execution");
            } else {
                project.getLogger().info("Transformed {} types", summary.getTransformed().size());
            }
        } finally {
            classLoaderResolver.close();
        }
    }

    /**
     * A {@link BuildLogger} implementation for a Gradle {@link Logger}.
     */
    protected static class GradleBuildLogger implements BuildLogger {

        /**
         * The logger to delegate to.
         */
        private final Logger logger;

        /**
         * Creates a new Gradle build logger.
         *
         * @param logger The logger to delegate to.
         */
        protected GradleBuildLogger(Logger logger) {
            this.logger = logger;
        }

        /**
         * {@inheritDoc}
         */
        public boolean isDebugEnabled() {
            return logger.isDebugEnabled();
        }

        /**
         * {@inheritDoc}
         */
        public void debug(String message) {
            logger.debug(message);
        }

        /**
         * {@inheritDoc}
         */
        public void debug(String message, Throwable throwable) {
            logger.debug(message, throwable);
        }

        /**
         * {@inheritDoc}
         */
        public boolean isInfoEnabled() {
            return logger.isInfoEnabled();
        }

        /**
         * {@inheritDoc}
         */
        public void info(String message) {
            logger.info(message);
        }

        /**
         * {@inheritDoc}
         */
        public void info(String message, Throwable throwable) {
            logger.info(message, throwable);
        }

        /**
         * {@inheritDoc}
         */
        public boolean isWarnEnabled() {
            return logger.isWarnEnabled();
        }

        /**
         * {@inheritDoc}
         */
        public void warn(String message) {
            logger.warn(message);
        }

        /**
         * {@inheritDoc}
         */
        public void warn(String message, Throwable throwable) {
            logger.warn(message, throwable);
        }

        /**
         * {@inheritDoc}
         */
        public boolean isErrorEnabled() {
            return logger.isErrorEnabled();
        }

        /**
         * {@inheritDoc}
         */
        public void error(String message) {
            logger.error(message);
        }

        /**
         * {@inheritDoc}
         */
        public void error(String message, Throwable throwable) {
            logger.error(message, throwable);
        }
    }

    /**
     * A {@link Plugin.Engine.Listener} that logs several relevant events during the build.
     */
    protected static class TransformationLogger extends Plugin.Engine.Listener.Adapter {

        /**
         * The logger to delegate to.
         */
        private final Logger logger;

        /**
         * Creates a new transformation logger.
         *
         * @param logger The logger to delegate to.
         */
        protected TransformationLogger(Logger logger) {
            this.logger = logger;
        }

        @Override
        public void onTransformation(TypeDescription typeDescription, List<Plugin> plugins) {
            logger.debug("Transformed {} using {}", typeDescription, plugins);
        }

        @Override
        public void onError(TypeDescription typeDescription, Plugin plugin, Throwable throwable) {
            logger.warn("Failed to transform {} using {}", typeDescription, plugin, throwable);
        }

        @Override
        public void onError(Map<TypeDescription, List<Throwable>> throwables) {
            logger.warn("Failed to transform {} types", throwables.size());
        }

        @Override
        public void onError(Plugin plugin, Throwable throwable) {
            logger.error("Failed to close {}", plugin, throwable);
        }

        @Override
        public void onLiveInitializer(TypeDescription typeDescription, TypeDescription definingType) {
            logger.debug("Discovered live initializer for {} as a result of transforming {}", definingType, typeDescription);
        }
    }
}

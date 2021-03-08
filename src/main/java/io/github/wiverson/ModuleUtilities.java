package io.github.wiverson;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.codehaus.plexus.util.FileUtils;
import org.moditect.commands.AddModuleInfo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

@Mojo(name = "collect-modules", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class ModuleUtilities extends AbstractMojo {

    private Log logger;

    @Parameter(defaultValue = "${project.compileClasspathElements}", readonly = true, required = true)
    private List<String> compilePath;

    @Override
    public void setLog(org.apache.maven.plugin.logging.Log log) {
        this.logger = log;
    }


    private boolean isModule(JarFile jar) {

        // If it has a module-info.class in the root, it's a module
        ZipEntry zipEntry = jar.getEntry("module-info.class");
        if (zipEntry != null)
            return true;

        // If it's a multi-release, might be a module
        zipEntry = jar.getEntry("META-INF/versions");

        // Nope, not a multi-release, so it's not a module
        if (zipEntry == null)
            return false;

        for (int i = 8; i < javaVersion; i++) {
            zipEntry = jar.getEntry("META-INF/versions/" + i + "/module-info.class");
            if (zipEntry != null)
                return true;
        }

        return false;
    }


    private void generateModuleInfo(File jarFile) throws MojoExecutionException, IOException {
        RunTool runTool = new RunTool(getLog(), debug, debug, true);

        List<String> arguments = new ArrayList<>();
        arguments.add("--ignore-missing-deps");
        arguments.add("--api-only");
        arguments.add("--no-recursive");
        arguments.add("--add-modules=ALL-MODULE-PATH");
        arguments.add("--module-path");
        arguments.add(buildModulesDirectory());
        arguments.add("--generate-module-info");
        arguments.add(moduleInfoWorkDirectory.getAbsolutePath());
        arguments.add(jarFile.getAbsolutePath());

        if (debug) {
            for (String s : arguments)
                logger.info(s);
        }
        runTool.runTool("jdeps", arguments, true);
    }

    private void addModuleInfo(File jarFile) throws IOException {
        AddModuleInfo addModuleInfo = new AddModuleInfo(
                findModInfo(jarFile),
                null,
                Integer.toString(1),
                jarFile.toPath(),
                notModulesDirectory.toPath(),
                null,
                true);
        addModuleInfo.run();
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        List<File> needsModuleInfo = new ArrayList<>();

        int foundModules = 0;
        int foundWithoutModules = 0;

        for (String s : compilePath) {
            File entry = new File(s);
            if (!entry.isDirectory())
                try {
                    JarFile jarFile = new JarFile(entry);
                    if (!skip(jarFile))
                        if (!isModule(jarFile)) {
                            if (debug)
                                logger.info(s + " is NOT a module, generating module info");
                            FileUtils.copyFileToDirectory(entry, notModulesDirectory);
                            File added = new File(notModulesDirectory, entry.getName());
                            foundWithoutModules++;
                            needsModuleInfo.add(added);
                        } else {
                            if (debug)
                                logger.info(s + " IS a module");
                            foundModules++;
                            FileUtils.copyFileToDirectory(entry, foundModulesDirectory);
                        }
                } catch (IOException e) {
                    logger.error(e);
                    throw new MojoFailureException(e.getMessage());
                }
        }

        for (File jar : needsModuleInfo) {
            try {
                if (debug)
                    logger.info("Generating info for " + jar.getName());
                generateModuleInfo(jar);
            } catch (IOException e) {
                logger.error(e);
                throw new MojoFailureException(e.getMessage());
            }
        }

        for (File jar : needsModuleInfo) {
            try {
                if (debug)
                    logger.info("Adding info for " + jar.getName());
                addModuleInfo(jar);
            } catch (IOException e) {
                logger.error(e);
                throw new MojoFailureException(e.getMessage());
            }
        }

        logger.info("Found " + foundModules + " modular jars and " + foundWithoutModules + " ordinary jars.");
    }

    private String buildModulesDirectory() {

        StringBuilder result = new StringBuilder();
        result.append(foundModulesDirectory.getAbsolutePath());
        result.append(File.pathSeparator);
        result.append(notModulesDirectory.getAbsolutePath());
        if (providedModuleDirectories.size() > 0) {
            for (File file : providedModuleDirectories) {
                result.append(File.pathSeparator);
                result.append(file.getAbsolutePath());
            }
        }

        return result.toString();
    }

    private String findModInfo(File jar) throws IOException {
        File jarFile = new File(jar.getName());
        String matchName = jarFile.getName().replace("-", ".");

        if (moduleInfoWorkDirectory == null)
            throw new IOException("No module info output directory set");
        if (!moduleInfoWorkDirectory.exists())
            Files.createDirectories(moduleInfoWorkDirectory.toPath());

        if (!moduleInfoWorkDirectory.isDirectory())
            throw new IOException("module info output directory is not a directory");

        for (File file : moduleInfoWorkDirectory.listFiles()) {
            if (matchName.startsWith(file.getName())) {
                File result = new File(file + "/module-info.java");

                return Files.readString(Path.of(result.getAbsolutePath()), StandardCharsets.US_ASCII);
            }
        }

        throw new IllegalArgumentException("Unable to find a module info for " + jarFile);
    }

    private boolean skip(JarFile jarFile) {
        if (ignoreJars == null)
            logger.warn("No skip jars defined");

        if (ignoreJars != null)
            for (String s : ignoreJars)
                if (jarFile.getName().contains(s))
                    return true;
        return false;
    }

    @Parameter(required = true)
    private File moduleInfoWorkDirectory;

    /**
     * Where to place the transitive Maven dependencies that ARE packaged as modules
     */
    @Parameter(required = true)
    private File foundModulesDirectory;

    /**
     * Where to place the transitive Maven dependencies that are not packaged as modules
     */
    @Parameter(required = true)
    private File notModulesDirectory;

    /**
     * The directories for provided modules (e.g. JavaFX jmods)
     */
    @Parameter
    private List<File> providedModuleDirectories;

    @Parameter(alias = "ignoreJars")
    private List<String> ignoreJars;

    @Parameter
    private int javaVersion = Runtime.version().feature();

    @Parameter
    private boolean debug;
}

/*
 * Capsule
 * Copyright (c) 2014-2015, Parallel Universe Software Co. and Contributors. All rights reserved.
 *
 * This program and the accompanying materials are licensed under the terms
 * of the Eclipse Public License v1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Permission;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.Properties;
import static java.util.Collections.*;
import static java.util.Arrays.asList;

/**
 * An application capsule.
 * <p>
 * This API is to be used by caplets (custom capsules) to programmatically (rather than declaratively) configure the capsule and possibly provide custom behavior.
 * <p>
 * All non-final protected methods may be overridden by caplets. These methods will usually be called once, but they must be idempotent,
 * i.e. if called numerous times they must always return the same value, and produce the same effect as if called once.
 * <br>
 * Overridden methods need not be thread-safe, and are guaranteed to be called by a single thread at a time.
 * <br>
 * Overridable (non-final) methods <b>must never</b> be called directly by caplet code, except by their overrides.
 * <p>
 * Final methods implement various utility or accessors, which may be freely used by caplets.
 * <p>
 * Caplets might consider overriding one of the following powerful methods:
 * {@link #attribute(Map.Entry) attribute}, {@link #getVarValue(String) getVarValue},
 * {@link #processOutgoingPath(Path) processOutgoingPath}, {@link #prelaunch(List) prelaunch}.
 * <p>
 * For command line option handling, see {@link #OPTION(String, String, String, String) OPTION}.<br/>
 * Attributes should be registered with {@link #ATTRIBUTE(String, String, boolean, String) ATTRIBUTE}.
 *
 * @author pron
 */
public class Capsule implements Runnable {
    public static final String VERSION = "1.0";
    /*
     * This class follows some STRICT RULES:
     *
     * 1. IT MUST COMPILE TO A SINGLE CLASS FILE (so it must not contain nested or inner classes).
     * 2. IT MUST ONLY REFERENCE CLASSES IN THE JDK.
     * 3. ALL METHODS MUST BE PURE OR, AT LEAST, IDEMPOTENT (with the exception of the launch method, and the constructor).
     *
     * Rules #1 and #2 ensure that fat capsules will work with only Capsule.class included in the JAR. Rule #2 helps enforcing rules #1 and #3.
     * Rule #3 ensures methods can be called in any order (after construction completes), and makes maintenance and evolution of Capsule simpler.
     * This class contains several strange hacks to comply with rule #1.
     *
     * Also, the code is not meant to be the most efficient, but methods should be as independent and stateless as possible.
     * Other than those few methods called in the constructor, all others are can be called in any order, and don't rely on any state.
     *
     * We do a lot of data transformations that could benefit from Java 8's lambdas+streams, but we want Capsule to support Java 7.
     *
     * The JavaDoc could really benefit from https://bugs.openjdk.java.net/browse/JDK-4085608 to categorize methods into 
     * Caplet overrides properties, and utility categories.
     *
     *
     * Caplet Hierarcy (or chain)
     * --------------------------
     *
     * Capsule subclasses, i.e. caplets, may be arranged in a dynamic "inheritance" hierarchy, where each caplet modifies, or "subclasses" 
     * the previous ones in the chain. 
     * The first caplet in the chain (the highest in the hierarchy) is referenced by the 'oc' field, the last is referenced by 'cc', and
     * the previous caplet, the "superclass" is referenced by 'sup':
     *
     *  ____          ____          ____          ____
     * |    |   sup  |    |   sup  |    |   sup  |    |
     * | OC | <----- |    | <----- |    | <----- | CC |
     * |____|        |____|        |____|        |____|
     *
     * A wrapping capsule is inserted into the chain following the wrapped capsule.
     */

    //<editor-fold defaultstate="collapsed" desc="Constants">
    /////////// Constants ///////////////////////////////////
    private static final long START = System.nanoTime();
    private static final Map<String, Object[]> OPTIONS = new LinkedHashMap<>(20);
    private static final Map<String, Object[]> ATTRIBS = new LinkedHashMap<>(60);

    private static final String ENV_CACHE_DIR = "CAPSULE_CACHE_DIR";
    private static final String ENV_CACHE_NAME = "CAPSULE_CACHE_NAME";

    private static final String PROP_VERSION = OPTION("capsule.version", "false", "printVersion", "Prints the capsule and application versions.");
    private static final String PROP_MODES = OPTION("capsule.modes", "false", "printModes", "Prints all available capsule modes.");
    private static final String PROP_PRINT_JRES = OPTION("capsule.jvms", "false", "printJVMs", "Prints a list of all JVM installations found.");
    private static final String PROP_MERGE = OPTION("capsule.merge", null, "mergeCapsules", true, "Merges a wrapper capsule with a wrapped capsule.");
    private static final String PROP_HELP = OPTION("capsule.help", "false", "printHelp", "Prints this help message.");
    private static final String PROP_MODE = OPTION("capsule.mode", null, null, "Picks the capsule mode to run.");
    private static final String PROP_RESET = OPTION("capsule.reset", "false", null, "Resets the capsule cache before launching. The capsule to be re-extracted (if applicable), and other possibly cached files will be recreated.");
    private static final String PROP_LOG_LEVEL = OPTION("capsule.log", "quiet", null, "Picks a log level. Must be one of none, quiet, verbose, or debug.");
    private static final String PROP_CAPSULE_JAVA_HOME = OPTION("capsule.java.home", null, null, "Sets the location of the Java home (JVM installation directory) to use; If \'current\' forces the use of the JVM that launched the capsule.");
    private static final String PROP_CAPSULE_JAVA_CMD = OPTION("capsule.java.cmd", null, null, "Sets the path to the Java executable to use.");
    private static final String PROP_JVM_ARGS = OPTION("capsule.jvm.args", null, null, "Sets additional JVM arguments to use when running the application.");
    private static final String PROP_TRAMPOLINE = "capsule.trampoline";
    private static final String PROP_PROFILE = "capsule.profile";

    /*
     * Map.Entry<String, T> was chosen to represent an attribute because of rules 1 and 2.
     */
    /** The application's name. E.g. {@code "The Best Word Processor"} */
    protected static final Entry<String, String> ATTR_APP_NAME = ATTRIBUTE("Application-Name", T_STRING(), null, false, "The application's name");
    /** The application's unique ID. E.g. {@code "com.acme.bestwordprocessor"} */
    protected static final Entry<String, String> ATTR_APP_ID = ATTRIBUTE("Application-Id", T_STRING(), null, false, "The application's name");
    protected static final Entry<String, String> ATTR_APP_VERSION = ATTRIBUTE("Application-Version", T_STRING(), null, false, "The application's version string");
    protected static final Entry<String, List<String>> ATTR_CAPLETS = ATTRIBUTE("Caplets", T_LIST(T_STRING()), null, false, "A list of names of caplet classes -- if embedded in the capsule -- or Maven coordinates of caplet artifacts that will be applied to the capsule in the order they are listed");
    private static final Entry<String, String> ATTR_LOG_LEVEL = ATTRIBUTE("Capsule-Log-Level", T_STRING(), null, false, "The capsule's default log level");
    private static final Entry<String, String> ATTR_MODE_DESC = ATTRIBUTE("Description", T_STRING(), null, true, "Contains the description of its respective mode");
    protected static final Entry<String, String> ATTR_APP_CLASS = ATTRIBUTE("Application-Class", T_STRING(), null, true, "The main application class");
    protected static final Entry<String, String> ATTR_APP_ARTIFACT = ATTRIBUTE("Application", T_STRING(), null, true, "The Maven coordinates of the application's main JAR or the path of the main JAR within the capsule");
    private static final Entry<String, String> ATTR_SCRIPT = ATTRIBUTE("Application-Script", T_STRING(), null, true, "A startup script to be run *instead* of `Application-Class`, given as a path relative to the capsule's root");
    private static final Entry<String, Boolean> ATTR_EXTRACT = ATTRIBUTE("Extract-Capsule", T_BOOL(), true, true, "Whether or not the capsule JAR will be extracted to the filesystem");
    protected static final Entry<String, String> ATTR_MIN_JAVA_VERSION = ATTRIBUTE("Min-Java-Version", T_STRING(), null, true, "The lowest Java version required to run the application");
    protected static final Entry<String, String> ATTR_JAVA_VERSION = ATTRIBUTE("Java-Version", T_STRING(), null, true, "The highest version of the Java installation required to run the application");
    protected static final Entry<String, Map<String, String>> ATTR_MIN_UPDATE_VERSION = ATTRIBUTE("Min-Update-Version", T_MAP(T_STRING(), null), null, true, "A space-separated key-value ('=' separated) list mapping Java versions to the minimum update version required");
    protected static final Entry<String, Boolean> ATTR_JDK_REQUIRED = ATTRIBUTE("JDK-Required", T_BOOL(), false, true, "Whether or not a JDK is required to launch the application");
    private static final Entry<String, List<String>> ATTR_ARGS = ATTRIBUTE("Args", T_LIST(T_STRING()), null, true,
            "A list of command line arguments to be passed to the application; the UNIX shell-style special variables (`$*`, `$1`, `$2`, ...) can refer to the actual arguments passed on the capsule's command line; if no special var is used, the listed values will be prepended to the supplied arguments (i.e., as if `$*` had been listed last).");
    private static final Entry<String, Map<String, String>> ATTR_ENV = ATTRIBUTE("Environment-Variables", T_MAP(T_STRING(), null), null, true, "A list of environment variables that will be put in the applications environment; formatted \"var=value\" or \"var\"");
    protected static final Entry<String, List<String>> ATTR_JVM_ARGS = ATTRIBUTE("JVM-Args", T_LIST(T_STRING()), null, true, "A list of JVM arguments that will be used to launch the application's Java process");
    protected static final Entry<String, Map<String, String>> ATTR_SYSTEM_PROPERTIES = ATTRIBUTE("System-Properties", T_MAP(T_STRING(), ""), null, true, "A list of system properties that will be defined in the applications JVM; formatted \"prop=value\" or \"prop\"");
    protected static final Entry<String, List<String>> ATTR_APP_CLASS_PATH = ATTRIBUTE("App-Class-Path", T_LIST(T_STRING()), null, true, "A list of JARs, relative to the capsule root, that will be put on the application's classpath, in the order they are listed");
    protected static final Entry<String, String> ATTR_CAPSULE_IN_CLASS_PATH = ATTRIBUTE("Capsule-In-Class-Path", T_STRING(), "true", true, "Whether or not the capsule JAR itself is on the application's classpath");
    protected static final Entry<String, List<String>> ATTR_BOOT_CLASS_PATH = ATTRIBUTE("Boot-Class-Path", T_LIST(T_STRING()), null, true, "A list of JARs, dependencies, and/or directories, relative to the capsule root, that will be used as the application's boot classpath");
    protected static final Entry<String, List<String>> ATTR_BOOT_CLASS_PATH_A = ATTRIBUTE("Boot-Class-Path-A", T_LIST(T_STRING()), null, true, "A list of JARs dependencies, and/or directories, relative to the capsule root, that will be appended to the applications default boot classpath");
    protected static final Entry<String, List<String>> ATTR_BOOT_CLASS_PATH_P = ATTRIBUTE("Boot-Class-Path-P", T_LIST(T_STRING()), null, true, "A list of JARs dependencies, and/or directories, relative to the capsule root, that will be prepended to the applications default boot classpath");
    protected static final Entry<String, List<String>> ATTR_LIBRARY_PATH_A = ATTRIBUTE("Library-Path-A", T_LIST(T_STRING()), null, true, "A list of JARs and/or directories, relative to the capsule root, to be appended to the default native library path");
    protected static final Entry<String, List<String>> ATTR_LIBRARY_PATH_P = ATTRIBUTE("Library-Path-P", T_LIST(T_STRING()), null, true, "a list of JARs and/or directories, relative to the capsule root, to be prepended to the default native library path");
    protected static final Entry<String, String> ATTR_SECURITY_MANAGER = ATTRIBUTE("Security-Manager", T_STRING(), null, true, "The name of a class that will serve as the application's security-manager");
    protected static final Entry<String, String> ATTR_SECURITY_POLICY = ATTRIBUTE("Security-Policy", T_STRING(), null, true, "A security policy file, relative to the capsule root, that will be used as the security policy");
    protected static final Entry<String, String> ATTR_SECURITY_POLICY_A = ATTRIBUTE("Security-Policy-A", T_STRING(), null, true, "A security policy file, relative to the capsule root, that will be appended to the default security policy");
    protected static final Entry<String, Map<String, String>> ATTR_JAVA_AGENTS = ATTRIBUTE("Java-Agents", T_MAP(T_STRING(), ""), null, true, "A list of Java agents used by the application; formatted \"agent\" or \"agent=arg1,arg2...\", where agent is either the path to a JAR relative to the capsule root, or a Maven coordinate of a dependency");
    protected static final Entry<String, Map<String, String>> ATTR_NATIVE_AGENTS = ATTRIBUTE("Native-Agents", T_MAP(T_STRING(), ""), null, true,
            "A list of native JVMTI agents used by the application; formatted \"agent\" or \"agent=arg1,arg2...\", where agent is either the path to a native library, without the platform-specific suffix, relative to the capsule root. The native library file(s) can be embedded in the capsule or listed as Maven native dependencies using the Native-Dependencies-... attributes.");
    protected static final Entry<String, List<String>> ATTR_DEPENDENCIES = ATTRIBUTE("Dependencies", T_LIST(T_STRING()), null, true, "A list of Maven dependencies given as groupId:artifactId:version[(excludeGroupId:excludeArtifactId,...)]");
    protected static final Entry<String, Map<String, String>> ATTR_NATIVE_DEPENDENCIES = ATTRIBUTE("Native-Dependencies", T_MAP(T_STRING(), ""), null, true, "A list of Maven dependencies consisting of native library artifacts; each item can be a comma separated pair, with the second component being a new name to give the download artifact");

    // outgoing
    private static final String VAR_CAPSULE_APP = "CAPSULE_APP";
    private static final String VAR_CAPSULE_DIR = "CAPSULE_DIR";
    private static final String VAR_CAPSULE_JAR = "CAPSULE_JAR";
    private static final String VAR_CLASSPATH = "CLASSPATH";
    private static final String VAR_JAVA_HOME = "JAVA_HOME";

    private static final String PROP_CAPSULE_JAR = "capsule.jar";
    private static final String PROP_CAPSULE_DIR = "capsule.dir";
    private static final String PROP_CAPSULE_APP = "capsule.app";

    private static final String PROP_CAPSULE_APP_PID = "capsule.app.pid";

    // standard values
    private static final String PROP_JAVA_VERSION = "java.version";
    private static final String PROP_JAVA_HOME = "java.home";
    private static final String PROP_OS_NAME = "os.name";
    private static final String PROP_USER_HOME = "user.home";
    private static final String PROP_JAVA_LIBRARY_PATH = "java.library.path";
    private static final String PROP_FILE_SEPARATOR = "file.separator";
    private static final String PROP_PATH_SEPARATOR = "path.separator";
    private static final String PROP_JAVA_SECURITY_POLICY = "java.security.policy";
    private static final String PROP_JAVA_SECURITY_MANAGER = "java.security.manager";
    private static final String PROP_TMP_DIR = "java.io.tmpdir";

    private static final String ATTR_MANIFEST_VERSION = "Manifest-Version";
    private static final String ATTR_MAIN_CLASS = "Main-Class";
    private static final String ATTR_CLASS_PATH = "Class-Path";
    private static final String ATTR_IMPLEMENTATION_VERSION = "Implementation-Version";
    private static final String ATTR_IMPLEMENTATION_TITLE = "Implementation-Title";
    private static final String ATTR_IMPLEMENTATION_VENDOR = "Implementation-Vendor";
    private static final String ATTR_IMPLEMENTATION_URL = "Implementation-URL";

    private static final String FILE_SEPARATOR = System.getProperty(PROP_FILE_SEPARATOR);
    private static final char FILE_SEPARATOR_CHAR = FILE_SEPARATOR.charAt(0);
    private static final String PATH_SEPARATOR = System.getProperty(PROP_PATH_SEPARATOR);
    private static final String MANIFEST_NAME = "META-INF/MANIFEST.MF";

    // misc
    private static final String CAPSULE_PROP_PREFIX = "capsule.";
    private static final String CACHE_DEFAULT_NAME = "capsule";
    private static final String APP_CACHE_NAME = "apps";
    private static final String LOCK_FILE_NAME = ".lock";
    private static final String TIMESTAMP_FILE_NAME = ".extracted";
    private static final String CACHE_NONE = "NONE";
    private static final Object DEFAULT = new Object();
    private static final String SEPARATOR_DOT = "\\.";
    private static final Path WINDOWS_PROGRAM_FILES_1 = Paths.get("C:", "Program Files");
    private static final Path WINDOWS_PROGRAM_FILES_2 = Paths.get("C:", "Program Files (x86)");
    private static final int WINDOWS_MAX_CMD = 32500; // actually 32768 - http://blogs.msdn.com/b/oldnewthing/archive/2003/12/10/56028.aspx
    private static final ClassLoader MY_CLASSLOADER = Capsule.class.getClassLoader();
    private static final Permission PERM_UNSAFE_OVERRIDE = new RuntimePermission("unsafeOverride");

    private static final String OS_WINDOWS = "windows";
    private static final String OS_MACOS = "macos";
    private static final String OS_LINUX = "linux";
    private static final String OS_SOLARIS = "solaris";
    private static final String OS_UNIX = "unix";
    private static final String OS_POSIX = "posix";

    private static final Set<String> PLATFORMS = immutableSet(OS_WINDOWS, OS_MACOS, OS_LINUX, OS_SOLARIS, OS_UNIX, OS_POSIX);

    // logging
    private static final String LOG_PREFIX = "CAPSULE: ";
    protected static final int LOG_NONE = 0;
    protected static final int LOG_QUIET = 1;
    protected static final int LOG_VERBOSE = 2;
    protected static final int LOG_DEBUG = 3;
    private static final int PROFILE = Boolean.parseBoolean(System.getProperty(PROP_PROFILE, "false")) ? LOG_QUIET : LOG_DEBUG;

    // options
    private static final int OPTION_DEFAULT = 0;
    private static final int OPTION_METHOD = 1;
    private static final int OPTION_WRAPPER_ONLY = 2;
    private static final int OPTION_DESC = 3;

    // attributes
    private static final int ATTRIB_TYPE = 0;
    private static final int ATTRIB_DEFAULT = 1;
    private static final int ATTRIB_MODAL = 2;
    private static final int ATTRIB_DESC = 3;
    //</editor-fold>

    //<editor-fold desc="Main">
    /////////// Main ///////////////////////////////////
    protected static final PrintStream STDOUT = System.out;
    protected static final PrintStream STDERR = System.err;
    private static final ThreadLocal<Integer> LOG_LEVEL = new ThreadLocal<>();
    private static Properties PROPERTIES = System.getProperties();
    private static final String OS = getProperty0(PROP_OS_NAME).toLowerCase();
    private static final String PLATFORM = getOS();
    private static Path CACHE_DIR;
    private static Capsule CAPSULE;

    final static Capsule myCapsule(List<String> args) {
        if (CAPSULE == null) {
            final ClassLoader ccl = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(MY_CLASSLOADER);
                final Capsule capsule = newCapsule(MY_CLASSLOADER, findOwnJarFile());
                clearContext();
                if (capsule.isEmptyCapsule() && !args.isEmpty()) {
                    processCmdLineOptions(args, ManagementFactory.getRuntimeMXBean().getInputArguments());
                    if (!args.isEmpty())
                        capsule.setTarget(args.remove(0));
                }
                CAPSULE = capsule.oc; // TODO: capsule or oc ???
            } finally {
                Thread.currentThread().setContextClassLoader(ccl);
            }
        }
        return CAPSULE;
    }

    public static final void main(String[] args) {
        System.exit(main0(args));
    }

    @SuppressWarnings({"BroadCatchBlock", "UnusedAssignment"})
    private static int main0(String[] args0) {
        List<String> args = new ArrayList<>(asList(args0)); // list must be mutable b/c myCapsule() might mutate it
        Capsule capsule = null;
        try {
            processOptions();
            capsule = myCapsule(args);

            args = unmodifiableList(args);

            if (isWrapperFactoryCapsule(capsule)) {
                capsule = null; // help gc
                return runOtherCapsule(args);
            }

            if (runActions(capsule, args))
                return 0;

            return capsule.launch(args);
        } catch (Throwable t) {
            if (capsule != null)
                capsule.onError(t);
            else
                printError(t, capsule);
            return 1;
        }
    }

    private static void printError(Throwable t, Capsule capsule) {
        STDERR.print("CAPSULE EXCEPTION: " + t.getMessage());
        if (hasContext() && (t.getMessage() == null || t.getMessage().length() < 50))
            STDERR.print(" while processing " + getContext());
        if (getLogLevel(getProperty0(PROP_LOG_LEVEL)) >= LOG_VERBOSE) {
            STDERR.println();
            deshadow(t).printStackTrace(STDERR);
        } else
            STDERR.println(" (for stack trace, run with -D" + PROP_LOG_LEVEL + "=verbose)");
        if (t instanceof IllegalArgumentException)
            printHelp(capsule != null ? capsule.isWrapperCapsule() : true);
    }

    //<editor-fold defaultstate="collapsed" desc="Run Other Capsule">
    /////////// Run Other Capsule ///////////////////////////////////
    private static boolean isWrapperFactoryCapsule(Capsule capsule) {
        return capsule.isFactoryCapsule() && capsule.isWrapperCapsule() && capsule.getJarFile() != null;
    }

    private static int runOtherCapsule(List<String> args) {
        final Path jar = CAPSULE.getJarFile();
        CAPSULE = null; // help gc
        return runMain(jar, args);
    }

    private static int runMain(Path jar, List<String> args) {
        final String mainClass;
        try {
            mainClass = getMainClass(jar);
            if (mainClass == null)
                throw new IllegalArgumentException("JAR file " + jar + " is not an executable (does not have a main class)");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(jar + " does not exist or does appear to be a valid JAR", e);
        }
        try {
            final Method main = newClassLoader0(null, jar).loadClass(mainClass).getMethod("main", String[].class);
            try {
                main.invoke(null, (Object) args.toArray(new String[0]));
                return 0;
            } catch (Exception e) {
                deshadow(e).printStackTrace(STDERR);
                return 1;
            }
        } catch (ReflectiveOperationException e) {
            throw rethrow(e);
        }
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Command Line">
    /////////// Command Line ///////////////////////////////////
    /**
     * Registers a capsule command-line option. Must be called during the caplet's static initialization.
     * <p>
     * Capsule options are system properties beginning with the prefix ".capsule", normally passed to the capsule as -D flags on the command line.
     * <p>
     * Options can be top-level *actions* (like print dependency tree or list JVMs), in which case the {@code methodName} argument must
     * be the name of a method used to launch the action instead of launching the capsule.
     * <p>
     * Options can have a default value, which will be automatically assigned to the system property if undefined. The default values
     * {@code "true"} and {@code "false"} are treated specially. If one of them is the assigned default value, and the system property
     * is defined with with a value of the empty string, then it will be re-assigned the value {@code "true"}.
     * <p>
     * <b>Simple Command Line Options for Wrapper Capsules</b><br>
     * When the capsule serves as a wrapper (i.e. it's an empty capsule used to launch an executable artifact or another capsule)
     * then the options can also be passed to the capsule as simple command line options (arguments starting with a hyphen),
     * with the "capsule." prefix removed, and every '.' character replaced with a '-'.
     * <p>
     * These command line arguments will automatically be converted to system properties, which will take their value from the argument
     * following the option (i.e. {@code -option value}), <i>unless</i> the option is given one of the special default values
     * {@code "true"} or {@code "false"}, in which case it is treated as a flag with no arguments (note that an option with the default
     * value {@code "true"} will therefore not be able to be turned off if simple options are used).
     *
     * @param defaultValue the option's default value ({@code "true"} and {@code "false"} are specially treated; see above).
     * @param optionName   the name of the system property for the option; must begin with {@code "capsule."}.
     * @param methodName   if non-null, then the option is a top-level action (like print dependency tree or list JVMs),
     *                     and this is the method which will run the action.
     *                     The method must accept a single {@code args} parameter of type {@code List<String>}.
     * @param wrapperOnly  whether or not the option is available in wrapper capsules only
     * @param description  a description of the option.
     * @return the option's name
     */
    protected static final String OPTION(String optionName, String defaultValue, String methodName, boolean wrapperOnly, String description) {
        if (!optionName.startsWith(CAPSULE_PROP_PREFIX))
            throw new IllegalArgumentException("Option name must start with " + CAPSULE_PROP_PREFIX + " but was " + optionName);
        final Object[] conf = new Object[]{defaultValue, methodName, wrapperOnly, description};
        final Object[] old = OPTIONS.get(optionName);
        if (old != null) {
            if (asList(conf).subList(0, conf.length - 1).equals(asList(old).subList(0, conf.length - 1))) // don't compare description
                throw new IllegalStateException("Option " + optionName + " has a conflicting registration: " + Arrays.toString(old));
        }
        OPTIONS.put(optionName, conf);
        return optionName;
    }

    /**
     * Same as {@link #OPTION(String, String, String, boolean, String) OPTION(optionName, defaultValue, methodName, wrapperOnly, description)}.
     */
    protected static final String OPTION(String optionName, String defaultValue, String methodName, String description) {
        return OPTION(optionName, defaultValue, methodName, false, description);
    }

    private static boolean optionTakesArguments(String propertyName) {
        final String defaultValue = (String) OPTIONS.get(propertyName)[OPTION_DEFAULT];
        return !("false".equals(defaultValue) || "true".equals(defaultValue));
    }

    private static void processOptions() {
        for (Map.Entry<String, Object[]> entry : OPTIONS.entrySet()) {
            final String option = entry.getKey();
            final String defval = (String) entry.getValue()[OPTION_DEFAULT];
            if (getProperty0(option) == null && defval != null && !defval.equals("false")) // the last condition is for backwards compatibility
                setProperty(option, defval);
            else if (!optionTakesArguments(option) && "".equals(getProperty0(option)))
                setProperty(option, "true");
        }
    }

    private static void processCmdLineOptions(List<String> args, List<String> jvmArgs) {
        while (!args.isEmpty()) {
            if (!args.get(0).startsWith("-"))
                break;
            final String arg = args.remove(0);

            String optarg = null;
            if (arg.contains("="))
                optarg = getAfter(arg, '=');

            final String option = simpleToOption(getBefore(arg, '='));
            if (option == null)
                throw new IllegalArgumentException("Unrecognized option: " + arg);

            // -D wins over simple flags
            boolean overridden = false;
            for (String x : jvmArgs) {
                if (x.equals("-D" + option) || x.startsWith("-D" + option + "=")) {
                    overridden = true;
                    break;
                }
            }

            if (optarg == null)
                optarg = optionTakesArguments(option) ? args.remove(0) : "";

            if (!overridden)
                setProperty(option, optarg);
        }
        processOptions();
    }

    // visible for testing
    @SuppressWarnings("unchecked")
    static final boolean runActions(Capsule capsule, List<String> args) {
        try {
            boolean found = false;
            for (Map.Entry<String, Object[]> entry : OPTIONS.entrySet()) {
                if (entry.getValue()[OPTION_METHOD] != null && systemPropertyEmptyOrNotFalse(entry.getKey())) {
                    if (!capsule.isWrapperCapsule() && (Boolean) entry.getValue()[OPTION_WRAPPER_ONLY])
                        throw new IllegalStateException("Action " + entry.getKey() + " is availbale for wrapper capsules only.");
                    final Method m = getMethod(capsule, (String) entry.getValue()[OPTION_METHOD], List.class);
                    m.invoke(capsule.cc.sup((Class<? extends Capsule>) m.getDeclaringClass()), args);
                    found = true;
                }
            }
            return found;
        } catch (InvocationTargetException e) {
            throw rethrow(e);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static String optionToSimple(String option) {
        return "-" + camelCaseToDashed(option.substring(CAPSULE_PROP_PREFIX.length())).replace('.', '-');
    }

    private static String simpleToOption(String simple) {
        if ("-h".equals(simple))
            return PROP_HELP;
        for (String option : OPTIONS.keySet()) {
            if (simple.equals(optionToSimple(option)))
                return option;
        }
        return null;
    }

    private static String camelCaseToDashed(String camel) {
        return camel.replaceAll("([A-Z][a-z]+)", "-$1").toLowerCase();
    }

    private static boolean isCapsuleOption(String propertyName) {
        return propertyName.startsWith(CAPSULE_PROP_PREFIX); // OPTIONS.containsKey(propertyName);
    }
    //</editor-fold>
    //</editor-fold>

    private static Map<String, List<Path>> JAVA_HOMES; // an optimization trick (can be injected by CapsuleLauncher)

    // fields marked /*final*/ are effectively final after finalizeCapsule
    private /*final*/ Capsule oc;  // first in chain
    private /*final*/ Capsule cc;  // last in chain
    private /*final*/ Capsule sup; // previous in chain
    private /*final*/ Capsule _ct; // a temp var

    private final boolean wrapper;
    private final Manifest manifest;     // never null
    private /*final*/ Path jarFile;      // never null
    private /*final*/ String appId;      // null iff wrapper capsule wrapping a non-capsule JAR
    private /*final*/ String mode;

    private Path javaHome;
    private Path cacheDir;
    private Path appCache;
    private Path writableAppCache;
    private boolean cacheUpToDate;
    private FileLock appCacheLock;

    // Some very limited state
    private List<String> jvmArgs_;
    private List<String> args_;
    private List<Path> tmpFiles = new ArrayList<>();
    private Process child;
    // Error reporting
    private static final ThreadLocal<String> contextType_ = new ThreadLocal<>();
    private static final ThreadLocal<String> contextKey_ = new ThreadLocal<>();
    private static final ThreadLocal<String> contextValue_ = new ThreadLocal<>();

    //<editor-fold defaultstate="collapsed" desc="Constructors">
    /////////// Constructors ///////////////////////////////////
    /*
     * The constructors and methods in this section may be reflectively called by CapsuleLauncher
     */
    /**
     * Constructs a capsule.
     * <p>
     * This constructor is used by a caplet that will be listed in the manifest's {@code Main-Class} attribute.
     * <b>Caplets are encouraged to "override" the {@link #Capsule(Capsule) other constructor} so that they may be listed
     * in the {@code Caplets} attribute.</b>
     * <p>
     * This constructor or that of a subclass must not make use of any registered capsule options,
     * as they may not have been properly pre-processed yet.
     *
     * @param jarFile the path to the JAR file
     */
    @SuppressWarnings({"OverridableMethodCallInConstructor", "LeakingThisInConstructor"})
    protected Capsule(Path jarFile) {
        clearContext();
        Objects.requireNonNull(jarFile, "jarFile can't be null");

        this.oc = this;
        this.cc = this;
        this.sup = null;

        this.jarFile = toAbsolutePath(jarFile);

        final long start = System.nanoTime(); // can't use clock before log level is set
        try (JarInputStream jis = openJarInputStream(jarFile)) {
            this.manifest = jis.getManifest();
            if (manifest == null)
                throw new RuntimeException("Capsule " + jarFile + " does not have a manifest");
        } catch (IOException e) {
            throw new RuntimeException("Could not read JAR file " + jarFile, e);
        }

        setLogLevel(chooseLogLevel()); // temporary

        log(LOG_VERBOSE, "Jar: " + jarFile);
        log(LOG_VERBOSE, "Platform: " + PLATFORM);

        loadCaplets();
        this.wrapper = isEmptyCapsule();

        setLogLevel(chooseLogLevel()); // temporary
        time("Load class", START, start);
        time("Read JAR in constructor", start);

        if (!wrapper)
            finalizeCapsule();
        else if (isFactoryCapsule())
            this.jarFile = null; // an empty factory capsule is marked this way.
        clearContext();
    }

    /**
     * Caplets that will be listed on the manifest's {@code Caplets} attribute must use this constructor.
     * Caplets are required to have a constructor with the same signature as this constructor, and pass their arguments to up to this constructor.
     *
     * @param pred The capsule preceding this one in the chain (caplets must not access the passed capsule in their constructor).
     */
    @SuppressWarnings("LeakingThisInConstructor")
    protected Capsule(Capsule pred) {
        this.oc = pred.oc;
        this.cc = this;

        time("Load class", START);
        clearContext();

        // insertAfter(pred);
        // copy final dields
        this.wrapper = pred.wrapper;
        this.manifest = pred.manifest;
        this.jarFile = pred.jarFile;
    }

    final Capsule setTarget(String target) {
        verifyCanCallSetTarget();
        final Path jar = toAbsolutePath(isDependency(target) ? firstOrNull(resolveDependency(target, "jar")) : Paths.get(target));
        if (jar == null)
            throw new RuntimeException(target + " not found.");
        return setTarget(jar);
    }

    // called directly by tests
    final Capsule setTarget(Path jar) {
        verifyCanCallSetTarget();

        jar = toAbsolutePath(jar);

        if (jar.equals(getJarFile())) // catch simple loops
            throw new RuntimeException("Capsule wrapping loop detected with capsule " + getJarFile());

        if (isFactoryCapsule()) {
            this.jarFile = jar;
            return this;
        }

        final Manifest man;
        boolean isCapsule = false;
        final long start = clock();
        try (JarInputStream jis = openJarInputStream(jar)) {
            man = jis.getManifest();
            if (man == null || man.getMainAttributes().getValue(ATTR_MAIN_CLASS) == null)
                throw new IllegalArgumentException(jar + " is not a capsule or an executable JAR");

            for (JarEntry entry; (entry = jis.getNextJarEntry()) != null;) {
                if (entry.getName().equals(Capsule.class.getName() + ".class")) {
                    isCapsule = true;
                    break;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read JAR file " + jar, e);
        }
        time("Read JAR in setTarget", start);

        if (!isCapsule)
            manifest.getMainAttributes().putValue(ATTR_APP_ARTIFACT.getKey(), jar.toString());
        else {
            log(LOG_VERBOSE, "Wrapping capsule " + jar);
            insertAfter(loadTargetCapsule(cc.getClass().getClassLoader(), jar).cc);
        }
        finalizeCapsule();
        return this;
    }

    /**
     * Called once the capsule construction has been completed (after loading of wrapped capsule, if applicable).
     */
    protected void finalizeCapsule() {
        if ((_ct = getCallTarget(Capsule.class)) != null)
            _ct.finalizeCapsule();
        else
            finalizeCapsule0();
        clearContext();
    }

    private void finalizeCapsule0() {
        validateManifest(oc.manifest);
        setLogLevel(chooseLogLevel());
        oc.mode = chooseMode1();
        initAppId();

        if (getAppId() == null && !(hasAttribute(ATTR_APP_ARTIFACT) && !isDependency(getAttribute(ATTR_APP_ARTIFACT))))
            throw new IllegalArgumentException("Could not determine app ID. Capsule jar " + getJarFile() + " should have the " + ATTR_APP_NAME + " manifest attribute.");
    }

    private void verifyCanCallSetTarget() {
        if (getAppId() != null)
            throw new IllegalStateException("Capsule is finalized");
        if (!isEmptyCapsule())
            throw new IllegalStateException("Capsule " + getJarFile() + " isn't empty");
    }

    private void loadCaplets() {
        for (String caplet : getAttribute(ATTR_CAPLETS))
            loadCaplet(caplet, cc).insertAfter(cc);
    }

    private void initAppId() {
        if (oc.appId != null)
            return;
        log(LOG_VERBOSE, "Initializing app ID");
        final String name = getAppIdNoVer();
        if (name == null)
            return;
        final String version = getAttribute(ATTR_APP_VERSION);
        oc.appId = name + (version != null ? "_" + version : "");
        log(LOG_VERBOSE, "Initialized app ID: " + oc.appId);
    }

    protected final boolean isEmptyCapsule() {
        return !hasAttribute(ATTR_APP_ARTIFACT) && !hasAttribute(ATTR_APP_CLASS) && !hasAttribute(ATTR_SCRIPT);
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Caplet Chain">
    /////////// Caplet Chain ///////////////////////////////////
    private Capsule loadCaplet(String caplet, Capsule pred) {
        log(LOG_VERBOSE, "Loading caplet: " + caplet);
        if (isDependency(caplet) || caplet.endsWith(".jar")) {
            final List<Path> jars = resolve(caplet);
            if (jars.size() != 1)
                throw new RuntimeException("The caplet " + caplet + " has transitive dependencies.");
            return newCapsule(jars.get(0), pred);
        } else
            return newCapsule(caplet, pred);
    }

    private void insertAfter(Capsule pred) {
        // private b/c this might be a security risk (wrapped capsule inserting a caplet after wrapper)
        // and also because it might be too powerful and prevent us from adopting a different caplet chain implementation
        log(LOG_VERBOSE, "Applying caplet " + this.getClass().getName());
        if (sup == pred)
            return;
        if (pred != null) {
            if (sup != null)
                throw new IllegalStateException("Caplet " + this + " is already in the chain (after " + sup + ")");
            if (!wrapper && pred.hasCaplet(this.getClass().getName())) {
                log(LOG_VERBOSE, "Caplet " + this.getClass().getName() + " has already been applied.");
                return;
            }

            this.sup = pred;
            this.oc = sup.oc;
            for (Capsule c = cc; c != this; c = c.sup)
                c.oc = oc;
            if (sup.cc == sup) { // I'm last
                for (Capsule c = sup; c != null; c = c.sup)
                    c.cc = cc;
            } else { // I'm in the middle
                for (Capsule c = sup.cc; c != sup; c = c.sup) {
                    if (c.sup == sup)
                        c.sup = cc;
                }
                for (Capsule c = cc; c != this; c = c.sup)
                    c.cc = sup.cc;
                this.cc = sup.cc;
            }
        }
    }

    /**
     * Checks whether a caplet with the given class name is installed.
     */
    protected final boolean hasCaplet(String name) {
        for (Capsule c = cc; c != null; c = c.sup) {
            for (Class<?> cls = c.getClass(); cls != null; cls = cls.getSuperclass()) {
                if (name.equals(cls.getClass().getName()))
                    return true;
            }
        }
        return false;
    }

    /**
     * The first caplet in the caplet chain starting with the current one and going up (back) that is of the requested type.
     */
    protected final <T extends Capsule> T sup(Class<T> caplet) {
        for (Capsule c = this; c != null; c = c.sup) {
            if (caplet.isInstance(c))
                return caplet.cast(c);
        }
        return null;
    }

    final <T extends Capsule> T getCallTarget(Class<T> clazz) {
        /*
         * Here we're implementing both the "invokevirtual" and "invokespecial".
         * We want to somehow differentiate the case where the function is called directly -- and should, like invokevirtual, target cc, the
         * last caplet in the hieracrchy -- from the case where the function is called with super.foo -- and should, like invokevirtual, 
         * target sup, the previous caplet in the hierarchy.
         */
        Capsule target = null;
        if ((sup == null || sup.sup(clazz) == null || this.jarFile != ((Capsule) sup.sup(clazz)).jarFile) && cc != this) { // the jarFile condition tests if this is the first caplet in a wrapper capsule
            final StackTraceElement[] st = new Throwable().getStackTrace();
            if (st == null || st.length < 3)
                throw new AssertionError("No debug information in Capsule class");

            final int c1 = 1;
            if (!st[c1].getClassName().equals(clazz.getName()))
                throw new RuntimeException("Illegal access. Method can only be called by the " + clazz.getName() + " class");

            int c2 = 2;
            while (isStream(st[c2].getClassName()))
                c2++;

            if (st[c1].getLineNumber() <= 0 || st[c2].getLineNumber() <= 0)
                throw new AssertionError("No debug information in Capsule class");

            // we return CC if the caller is also Capsule but not the same method (which would mean this is a sup.foo() call)
            if (!st[c2].getMethodName().equals(st[c1].getMethodName())
                    || (st[c2].getClassName().equals(clazz.getName()) && Math.abs(st[c2].getLineNumber() - st[c1].getLineNumber()) > 3))
                target = cc;
        }
        if (target == null)
            target = sup;

        return target != null ? target.sup(clazz) : null;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Properties">
    /////////// Properties ///////////////////////////////////
    private boolean isWrapperOfNonCapsule() {
        return getAppId() == null;
    }

    private boolean isFactoryCapsule() {
        if (!getClass().equals(Capsule.class) || !wrapper)
            return false;
        for (Object attr : manifest.getMainAttributes().keySet()) {
            if (ATTRIBS.containsKey(attr.toString())) // (!isCommonAttribute(attr.toString()))
                return false;
        }
        for (Attributes atts : manifest.getEntries().values()) {
            for (Object attr : atts.keySet()) {
                if (ATTRIBS.containsKey(attr.toString())) // (!isCommonAttribute(attr.toString()))
                    return false;
            }
        }
        log(LOG_DEBUG, "Factory (unchanged) capsule");
        return true;
    }

    /**
     * Whether or not this is an empty capsule
     */
    protected final boolean isWrapperCapsule() {
        for (Capsule c = cc; c != null; c = c.sup) {
            if (c.wrapper)
                return true;
        }
        return false;
    }

    /**
     * This capsule's current mode.
     */
    protected final String getMode() {
        return oc.mode;
    }

    /**
     * This capsule's JAR file.
     */
    protected final Path getJarFile() {
        return oc.jarFile;
    }

    /**
     * Returns the app's ID.
     */
    protected final String getAppId() {
        return oc.appId;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Capsule JAR">
    /////////// Capsule JAR ///////////////////////////////////
    private static Path findOwnJarFile() {
        final URL url = MY_CLASSLOADER.getResource(Capsule.class.getName().replace('.', '/') + ".class");
        if (!"jar".equals(url.getProtocol()))
            throw new IllegalStateException("The Capsule class must be in a JAR file, but was loaded from: " + url);
        final String path = url.getPath();
        if (path == null) //  || !path.startsWith("file:")
            throw new IllegalStateException("The Capsule class must be in a local JAR file, but was loaded from: " + url);

        try {
            final URI jarUri = new URI(path.substring(0, path.indexOf('!')));
            return Paths.get(jarUri);
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    private String toJarUrl(String relPath) {
        return "jar:file:" + getJarFile().toAbsolutePath() + "!/" + relPath;
    }

    private static boolean isExecutable(Path path) {
        if (!Files.isExecutable(path))
            return false;
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), "UTF-8")) {
            int c = reader.read();
            if (c < 0 || (char) c != '#')
                return false;
            c = reader.read();
            if (c < 0 || (char) c != '!')
                return false;
            return true;
        } catch (IOException e) {
            throw rethrow(e);
        }
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Main Operations">
    /////////// Main Operations ///////////////////////////////////
    void printVersion(List<String> args) {
        if (getAppId() != null) {
            STDOUT.println(LOG_PREFIX + "Application " + getAppId());
            if (hasAttribute(ATTR_APP_NAME))
                STDOUT.println(LOG_PREFIX + getAttribute(ATTR_APP_NAME));
            if (hasAttribute(ATTR_APP_VERSION))
                STDOUT.println(LOG_PREFIX + "Version: " + getAttribute(ATTR_APP_VERSION));
            for (String attr : asList(ATTR_IMPLEMENTATION_VENDOR, ATTR_IMPLEMENTATION_URL)) {
                if (getManifestAttribute(attr) != null)
                    STDOUT.println(LOG_PREFIX + getManifestAttribute(attr));
            }
        }
        STDOUT.println(LOG_PREFIX + "Capsule Version " + VERSION);
    }

    void printModes(List<String> args) {
        verifyNonEmpty("Cannot print modes of a wrapper capsule.");
        STDOUT.println(LOG_PREFIX + "Application " + getAppId());
        STDOUT.println("Available modes:");
        final Set<String> modes = getModes();
        if (modes.isEmpty())
            STDOUT.println("Default mode only");
        else {
            for (String m : modes) {
                final String desc = getModeDescription(m);
                STDOUT.println("* " + m + (desc != null ? ": " + desc : ""));
            }
        }
    }

    void printJVMs(List<String> args) {
        final Map<String, List<Path>> jres = getJavaHomes();
        if (jres == null)
            println("No detected Java installations");
        else {
            STDOUT.println(LOG_PREFIX + "Detected Java installations:");
            for (Map.Entry<String, List<Path>> j : jres.entrySet()) {
                for (Path home : j.getValue())
                    STDOUT.println(j.getKey() + (isJDK(home) ? " (JDK)" : "") + (j.getKey().length() < 8 ? "\t\t" : "\t") + home);
            }
        }
        final Path jhome = getJavaHome();
        STDOUT.println(LOG_PREFIX + "selected " + (jhome != null ? jhome : (getProperty(PROP_JAVA_HOME) + " (current)")));
    }

    void mergeCapsules(List<String> args) {
        if (!isWrapperCapsule())
            throw new IllegalStateException("This is not a wrapper capsule");
        try {
            final Path outCapsule = path(getProperty(PROP_MERGE));
            final Path wr = cc.jarFile;
            final Path wd = oc.jarFile;
            log(LOG_QUIET, "Merging " + wr + (!Objects.deepEquals(wr, wd) ? " + " + wd : "") + " -> " + outCapsule);
            mergeCapsule(wr, wd, outCapsule);
        } catch (Exception e) {
            throw new RuntimeException("Capsule merge failed.", e);
        }
    }

    void printHelp(List<String> args) {
        printHelp(wrapper);
    }

    private static void printHelp(boolean simple) {
        // USAGE:
        final Path myJar = toFriendlyPath(findOwnJarFile());
        final boolean executable = isExecutable(myJar);

        final StringBuilder usage = new StringBuilder();
        if (!executable)
            usage.append("java ");
        if (simple) {
            if (!executable)
                usage.append("-jar ");
            usage.append(myJar).append(' ');
        }
        usage.append("<options> ");
        if (!simple && !executable)
            usage.append("-jar ");
        if (simple)
            usage.append("<path or Maven coords of application JAR/capsule>");
        else
            usage.append(myJar);
        STDERR.println("USAGE: " + usage);

        // ACTIONS AND OPTIONS:
        for (boolean actions : new boolean[]{true, false}) {
            STDERR.println("\n" + (actions ? "Actions:" : "Options:"));
            for (Map.Entry<String, Object[]> entry : OPTIONS.entrySet()) {
                if (entry.getValue()[OPTION_DESC] != null && (entry.getValue()[OPTION_METHOD] != null) == actions) {
                    if (!simple && (Boolean) entry.getValue()[OPTION_WRAPPER_ONLY])
                        continue;
                    final String option = entry.getKey();
                    final String defaultValue = (String) entry.getValue()[OPTION_DEFAULT];
                    if (simple && !optionTakesArguments(option) && defaultValue.equals("true"))
                        continue;
                    StringBuilder sb = new StringBuilder();
                    sb.append(simple ? optionToSimple(option) : option);

                    if (optionTakesArguments(option) || defaultValue.equals("true")) {
                        sb.append(simple ? ' ' : '=').append("<value>");
                        if (defaultValue != null)
                            sb.append(" (default: ").append(defaultValue).append(")");
                    }
                    sb.append(" - ").append(entry.getValue()[OPTION_DESC]);

                    STDERR.println("  " + sb);
                }
            }
        }

        // ATTRIBUTES:
        if (1 == 2) {
            STDERR.println("\nManifest Attributes:");
            for (Map.Entry<String, Object[]> entry : ATTRIBS.entrySet()) {
                if (entry.getValue()[ATTRIB_DESC] != null) {
                    final String attrib = entry.getKey();
                    final String defaultValue = toString(entry.getValue()[ATTRIB_DEFAULT]);
                    StringBuilder sb = new StringBuilder();
                    sb.append(attrib);
                    if (defaultValue != null)
                        sb.append(" (default: ").append(defaultValue).append(")");
                    sb.append(" - ").append(entry.getValue()[ATTRIB_DESC]);

                    STDERR.println("  " + sb);
                }
            }
        }
    }

    private int launch(List<String> args) throws IOException, InterruptedException {
        verifyNonEmpty("Cannot launch a wrapper capsule.");
        final ProcessBuilder pb;
        try {
            final List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            pb = prepareForLaunch(jvmArgs, args);
            if (pb == null) { // can be null if prelaunch has been overridden by a subclass
                log(LOG_VERBOSE, "Nothing to run");
                return 0;
            }
        } catch (Exception t) {
            cleanup();
            throw t;
        }
        clearContext();

        time("Total", START);
        log(LOG_VERBOSE, join(pb.command(), " ") + (pb.directory() != null ? " (Running in " + pb.directory() + ")" : ""));

        if (isTrampoline()) {
            if (hasAttribute(ATTR_ENV))
                throw new RuntimeException("Capsule cannot trampoline because manifest defines the " + ATTR_ENV + " attribute.");
            pb.command().remove("-D" + PROP_TRAMPOLINE);
            STDOUT.println(join(pb.command(), " "));
        } else {
            Runtime.getRuntime().addShutdownHook(new Thread(this));

            if (!isInheritIoBug())
                pb.inheritIO();

            oc.child = pb.start();

            final int pid = getPid(oc.child);
            if (pid > 0)
                System.setProperty(PROP_CAPSULE_APP_PID, Integer.toString(pid));

            if (isInheritIoBug())
                pipeIoStreams();

            postlaunch(child);

            oc.child.waitFor();
        }

        return oc.child != null ? oc.child.exitValue() : 0;
    }

    private void verifyNonEmpty(String message) {
        if (isEmptyCapsule())
            throw new IllegalArgumentException(message);
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Launch">
    /////////// Launch ///////////////////////////////////
    // directly used by CapsuleLauncher
    final ProcessBuilder prepareForLaunch(List<String> jvmArgs, List<String> args) {
        final long start = clock();
        oc.jvmArgs_ = nullToEmpty(jvmArgs); // hack
        oc.args_ = nullToEmpty(jvmArgs);    // hack

        log(LOG_VERBOSE, "Launching app " + getAppId() + (getMode() != null ? " in mode " + getMode() : ""));
        try {
            final ProcessBuilder pb;
            try {
                pb = prelaunch(nullToEmpty(args));
                markCache();
                return pb;
            } finally {
                unlockAppCache();
                time("prepareForLaunch", start);
            }
        } catch (IOException e) {
            throw rethrow(e);
        }
    }

    /**
     * @deprecated marked deprecated to exclude from javadoc
     */
    @Override
    public final void run() {
        if (isInheritIoBug() && pipeIoStream())
            return;

        // shutdown hook
        cleanup();
    }

    /**
     * Called when the capsule exits after a successful or failed attempt to launch the application.
     * If you override this method, you must make sure to call {@code super.cleanup()} even in the event of an abnormal termination
     * (i.e. when an exception is thrown). This method must not throw any exceptions. All exceptions origination by {@code cleanup}
     * must be wither ignored completely or printed to STDERR.
     */
    protected void cleanup() {
        if ((_ct = getCallTarget(Capsule.class)) != null)
            _ct.cleanup();
        else
            cleanup0();
    }

    private void cleanup0() {
        try {
            if (oc.child != null)
                oc.child.destroy();
            oc.child = null;
        } catch (Exception t) {
            deshadow(t).printStackTrace(STDERR);
        }

        for (Path p : oc.tmpFiles) {
            try {
                delete(p);
            } catch (Exception t) {
                log(LOG_VERBOSE, t.getMessage());
            }
        }
        oc.tmpFiles.clear();
    }

    protected final Path addTempFile(Path p) {
        oc.tmpFiles.add(p);
        return p;
    }

    private String chooseMode1() {
        String m = chooseMode();
        if (m != null && !hasMode(m))
            throw new IllegalArgumentException("Capsule " + getJarFile() + " does not have mode " + m);
        return m;
    }

    /**
     * Chooses this capsule's mode.
     * The mode is chosen during the preparations for launch (not at construction time).
     */
    protected String chooseMode() {
        return (_ct = getCallTarget(Capsule.class)) != null ? _ct.chooseMode() : chooseMode0();
    }

    private String chooseMode0() {
        return emptyToNull(getProperty(PROP_MODE));
    }

    /**
     * Returns a configured {@link ProcessBuilder} that is later used to launch the capsule.
     * The ProcessBuilder's IO redirection is left in its default settings.
     * Caplets may override this method to display a message prior to launch, or to configure the process's IO streams.
     * For more elaborate manipulation of the Capsule's launched process, consider overriding {@link #buildProcess() buildProcess}.
     *
     * @param args the application command-line arguments
     * @return a configured {@code ProcessBuilder} (if {@code null}, the launch will be aborted).
     */
    protected ProcessBuilder prelaunch(List<String> args) {
        return (_ct = unsafe(getCallTarget(Capsule.class))) != null ? _ct.prelaunch(args) : prelaunch0(args);
    }

    private ProcessBuilder prelaunch0(List<String> args) {
        final ProcessBuilder pb = buildProcess();
        buildEnvironmentVariables(pb);
        pb.command().addAll(buildArgs(args));
        return pb;
    }

    /**
     * Constructs a {@link ProcessBuilder} that is later used to launch the capsule.
     * The returned process builder should contain the command <i>minus</i> the application arguments (which are later constructed by
     * {@link #buildArgs(List) buildArgs} and appended to the command).<br>
     * While environment variables may be set at this stage, the environment is later configured by
     * {@link #buildEnvironmentVariables(Map) buildEnvironmentVariables}.
     * <p>
     * This implementation tries to create a process running a startup script, and, if one has not been set, constructs a Java process.
     * <p>
     * This method should be overridden to add new types of processes the capsule can launch (like, say, Python scripts).
     * If all you want is to configure the returned {@link ProcessBuilder}, for example to set IO stream redirection,
     * you should override {@link #prelaunch(List) prelaunch}.
     *
     * @return a {@code ProcessBuilder} (must never be {@code null}).
     */
    protected ProcessBuilder buildProcess() {
        return (_ct = unsafe(getCallTarget(Capsule.class))) != null ? _ct.buildProcess() : buildProcess0();
    }

    private ProcessBuilder buildProcess0() {
        if (oc.jvmArgs_ == null)
            throw new IllegalStateException("Capsule has not been prepared for launch!");

        final ProcessBuilder pb = new ProcessBuilder();
        if (!buildScriptProcess(pb))
            buildJavaProcess(pb, oc.jvmArgs_, oc.args_);
        return pb;
    }

    /**
     * Returns a list of command line arguments to pass to the application.
     *
     * @param args The command line arguments passed to the capsule at launch
     */
    protected List<String> buildArgs(List<String> args) {
        return (_ct = getCallTarget(Capsule.class)) != null ? _ct.buildArgs(args) : buildArgs0(args);
    }

    private List<String> buildArgs0(List<String> args) {
        return expandArgs(getAttribute(ATTR_ARGS), args);
    }

    // visible for testing
    static List<String> expandArgs(List<String> args0, List<String> args) {
        final List<String> args1 = new ArrayList<String>();
        boolean expanded = false;
        for (String a : args0) {
            if (a.startsWith("$")) {
                if (a.equals("$*")) {
                    args1.addAll(args);
                    expanded = true;
                    continue;
                } else {
                    try {
                        final int i = Integer.parseInt(a.substring(1));
                        args1.add(args.get(i - 1));
                        expanded = true;
                        continue;
                    } catch (NumberFormatException e) {
                    }
                }
            }
            args1.add(a);
        }
        if (!expanded)
            args1.addAll(args);
        return args1;
    }

    private void buildEnvironmentVariables(ProcessBuilder pb) {
        Map<String, String> env = new HashMap<>(pb.environment());
        env = buildEnvironmentVariables(env);
        pb.environment().clear();
        pb.environment().putAll(env);
    }

    /**
     * Returns a map of environment variables (property-value pairs).
     *
     * @param env the current environment
     */
    protected Map<String, String> buildEnvironmentVariables(Map<String, String> env) {
        return (_ct = getCallTarget(Capsule.class)) != null ? _ct.buildEnvironmentVariables(env) : buildEnvironmentVariables0(env);
    }

    private Map<String, String> buildEnvironmentVariables0(Map<String, String> env) {
        final Map<String, String> jarEnv = getAttribute(ATTR_ENV);
        for (Map.Entry<String, String> e : jarEnv.entrySet()) {
            boolean overwrite = false;
            String var = e.getKey();
            if (var.endsWith(":")) {
                overwrite = true;
                var = var.substring(0, var.length() - 1);
            }

            if (overwrite || !env.containsKey(var))
                env.put(var, e.getValue() != null ? e.getValue() : "");
        }
        if (getAppId() != null) {
            if (getAppCache() != null)
                env.put(VAR_CAPSULE_DIR, processOutgoingPath(getAppCache()));
            env.put(VAR_CAPSULE_JAR, processOutgoingPath(getJarFile()));
            env.put(VAR_CAPSULE_APP, getAppId());
        }
        return env;
    }

    private static boolean isTrampoline() {
        return systemPropertyEmptyOrTrue(PROP_TRAMPOLINE);
    }

    /**
     * Called after the application is launched by the capsule.
     *
     * @param child the child process running the application
     */
    protected void postlaunch(Process child) {
        if ((_ct = getCallTarget(Capsule.class)) != null)
            _ct.postlaunch(child);
        else
            postlaunch0(child);
    }

    private void postlaunch0(Process child) {
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="App ID">
    /////////// App ID ///////////////////////////////////
    private String getAppIdNoVer() {
        String id = getAttribute(ATTR_APP_ID);
        if (isEmpty(id))
            id = getAttribute(ATTR_APP_NAME);
        if (id == null) {
            id = getAttribute(ATTR_APP_CLASS);
            if (id != null && hasModalAttribute(ATTR_APP_CLASS))
                throw new IllegalArgumentException("App ID-related attribute " + ATTR_APP_CLASS + " is defined in a modal section of the manifest. "
                        + " In this case, you must add the " + ATTR_APP_ID + " attribute to the manifest's main section.");
        }
        return id;
    }

    static String getAppArtifactId(String coords) {
        if (coords == null)
            return null;
        final String[] cs = coords.split(":");
        return cs[0] + "." + cs[1];
    }

    static String getAppArtifactVersion(String coords) {
        if (coords == null)
            return null;
        final String[] cs = coords.split(":");
        if (cs.length < 3)
            return null;
        return cs[2];
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Capsule Cache">
    /////////// Capsule Cache ///////////////////////////////////
    /**
     * @deprecated exclude from javadocs
     */
    protected Path getCacheDir() {
        if (oc.cacheDir == null) {
            Path cache = CACHE_DIR;
            if (cache != null) {
                cache = initCacheDir(cache);
            } else {
                final String cacheDirEnv = System.getenv(ENV_CACHE_DIR);
                if (cacheDirEnv != null) {
                    if (cacheDirEnv.equalsIgnoreCase(CACHE_NONE))
                        return null;
                    cache = initCacheDir(Paths.get(cacheDirEnv));
                    if (cache == null)
                        throw new RuntimeException("Could not initialize cache directory " + Paths.get(cacheDirEnv));
                } else {
                    final String name = getCacheName();
                    cache = initCacheDir(getCacheHome().resolve(name));
                    if (cache == null) {
                        try {
                            cache = addTempFile(Files.createTempDirectory(getTempDir(), "capsule-"));
                        } catch (IOException e) {
                            log(LOG_VERBOSE, "Could not create directory: " + cache + " -- " + e.getMessage());
                            cache = null;
                        }
                    }
                }
            }
            log(LOG_VERBOSE, "Cache directory: " + cache);
            oc.cacheDir = cache;
        }
        return oc.cacheDir;
    }

    private static String getCacheName() {
        final String cacheNameEnv = System.getenv(ENV_CACHE_NAME);
        final String cacheName = cacheNameEnv != null ? cacheNameEnv : CACHE_DEFAULT_NAME;
        return (isWindows() ? "" : ".") + cacheName;
    }

    private Path initCacheDir(Path cache) {
        try {
            if (!Files.exists(cache))
                Files.createDirectories(cache, getPermissions(getExistingAncestor(cache)));
            return cache;
        } catch (IOException e) {
            log(LOG_VERBOSE, "Could not create directory: " + cache + " -- " + e.getMessage());
            return null;
        }
    }

    private static Path getCacheHome() {
        final Path cacheHome;

        final Path userHome = Paths.get(getProperty(PROP_USER_HOME));
        if (!isWindows())
            cacheHome = userHome;
        else {
            Path localData;
            final String localAppData = getenv("LOCALAPPDATA");
            if (localAppData != null) {
                localData = Paths.get(localAppData);
                if (!Files.isDirectory(localData))
                    throw new RuntimeException("%LOCALAPPDATA% set to nonexistent directory " + localData);
            } else {
                localData = userHome.resolve(Paths.get("AppData", "Local"));
                if (!Files.isDirectory(localData))
                    localData = userHome.resolve(Paths.get("Local Settings", "Application Data"));
                if (!Files.isDirectory(localData))
                    throw new RuntimeException("%LOCALAPPDATA% is undefined, and neither "
                            + userHome.resolve(Paths.get("AppData", "Local")) + " nor "
                            + userHome.resolve(Paths.get("Local Settings", "Application Data")) + " have been found");
            }
            cacheHome = localData;
        }

        return cacheHome;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="App Cache">
    /////////// App Cache ///////////////////////////////////
    /**
     * This capsule's cache directory, or {@code null} if capsule has been configured not to extract, or the app cache dir hasn't been set up yet.
     */
    protected final Path getAppCache() {
        if (oc.appCache == null && shouldExtract())
            oc.appCache = buildAppCacheDir();
        return oc.appCache;
    }

    /**
     * Returns this capsule's cache directory.
     * The difference between this method and {@link #getAppCache()} is that this method throws an exception if the app cache
     * cannot be retrieved, while {@link #getAppCache()} returns {@code null}.
     *
     * @throws IllegalStateException if the app cache hasn't been set up (yet).
     */
    protected final Path verifyAppCache() {
        final Path dir = getAppCache();
        if (dir == null) {
            String message = "Capsule not extracted.";
            if (getAppId() == null) {
                if (isEmptyCapsule())
                    message += " This is a wrapper capsule and the wrapped capsule hasn't been set (yet)";
                else
                    message += " App ID has not been determined yet.";
            } else {
                if (!shouldExtract())
                    message += " The " + name(ATTR_EXTRACT) + " attribute has been set to false";
            }
            throw new IllegalStateException(message);
        }
        return dir;
    }

    /**
     * Returns a writable directory that can be used to store files related to launching the capsule.
     */
    protected final Path getWritableAppCache() {
        if (oc.writableAppCache == null) {
            Path cache = getAppCache();
            if (cache == null || !Files.isWritable(cache)) {
                try {
                    cache = addTempFile(Files.createTempDirectory(getTempDir(), "capsule-"));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            oc.writableAppCache = cache;
        }
        return oc.writableAppCache;
    }

    /**
     * Returns the path of the application cache (this is the directory where the capsule is extracted if necessary).
     */
    protected Path buildAppCacheDir() {
        return (_ct = unsafe(getCallTarget(Capsule.class))) != null ? _ct.buildAppCacheDir() : buildAppCacheDir0();
    }

    private Path buildAppCacheDir0() {
        initAppId();
        if (getAppId() == null)
            return null;

        try {
            final long start = clock();
            final Path dir = toAbsolutePath(getCacheDir().resolve(APP_CACHE_NAME).resolve(getAppId()));
            Files.createDirectories(dir, getPermissions(getExistingAncestor(dir)));

            this.cacheUpToDate = isAppCacheUpToDate1(dir);
            if (!cacheUpToDate) {
                resetAppCache(dir);
                if (shouldExtract())
                    extractCapsule(dir);
            } else
                log(LOG_VERBOSE, "App cache " + dir + " is up to date.");

            time("buildAppCacheDir", start);
            return dir;
        } catch (IOException e) {
            throw rethrow(e);
        }
    }

    private boolean shouldExtract() {
        return getAttribute(ATTR_EXTRACT);
    }

    private void resetAppCache(Path dir) throws IOException {
        try {
            log(LOG_DEBUG, "Creating cache for " + getJarFile() + " in " + dir.toAbsolutePath());
            final Path lockFile = dir.resolve(LOCK_FILE_NAME);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path f : ds) {
                    if (!lockFile.equals(f))
                        delete(f);
                }
            }
        } catch (IOException e) {
            throw new IOException("Exception while extracting jar " + getJarFile() + " to app cache directory " + dir.toAbsolutePath(), e);
        }
    }

    private boolean isAppCacheUpToDate1(Path dir) throws IOException {
        boolean res = testAppCacheUpToDate(dir);
        if (!res) {
            lockAppCache(dir);
            res = testAppCacheUpToDate(dir);
            if (res)
                unlockAppCache(dir);
        }
        return res;
    }

    private boolean testAppCacheUpToDate(Path dir) throws IOException {
        if (systemPropertyEmptyOrTrue(PROP_RESET))
            return false;

        Path extractedFile = dir.resolve(TIMESTAMP_FILE_NAME);
        if (!Files.exists(extractedFile))
            return false;
        FileTime extractedTime = Files.getLastModifiedTime(extractedFile);
        FileTime jarTime = Files.getLastModifiedTime(getJarFile());
        return extractedTime.compareTo(jarTime) >= 0;
    }

    /**
     * Extracts the capsule's contents into the app cache directory.
     * This method may be overridden to write additional files to the app cache.
     */
    protected void extractCapsule(Path dir) throws IOException {
        if ((_ct = getCallTarget(Capsule.class)) != null)
            _ct.extractCapsule(dir);
        else
            extractCapsule0(dir);
    }

    private void extractCapsule0(Path dir) throws IOException {
        try {
            log(LOG_VERBOSE, "Extracting " + getJarFile() + " to app cache directory " + dir.toAbsolutePath());
            extractJar(openJarInputStream(getJarFile()), dir);
        } catch (IOException e) {
            throw new IOException("Exception while extracting jar " + getJarFile() + " to app cache directory " + dir.toAbsolutePath(), e);
        }
    }

    private void markCache() throws IOException {
        if (oc.appCache == null || cacheUpToDate)
            return;
        if (Files.isWritable(oc.appCache))
            Files.createFile(oc.appCache.resolve(TIMESTAMP_FILE_NAME));
    }

    private void lockAppCache(Path dir) throws IOException {
        final Path lockFile = addTempFile(dir.resolve(LOCK_FILE_NAME));
        log(LOG_VERBOSE, "Locking " + lockFile);
        final FileChannel c = FileChannel.open(lockFile, new HashSet<>(asList(StandardOpenOption.CREATE, StandardOpenOption.WRITE)), getPermissions(dir));

        this.appCacheLock = c.lock();
    }

    private void unlockAppCache(Path dir) throws IOException {
        if (appCacheLock != null) {
            log(LOG_VERBOSE, "Unlocking " + dir.resolve(LOCK_FILE_NAME));
            appCacheLock.release();
            appCacheLock.acquiredBy().close();
            appCacheLock = null;
        }
    }

    private void unlockAppCache() throws IOException {
        if (oc.appCache == null)
            return;
        unlockAppCache(oc.appCache);
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Script Process">
    /////////// Script Process ///////////////////////////////////
    private Path getScript() {
        final String s = getAttribute(ATTR_SCRIPT);
        try {
            return s != null ? sanitize(verifyAppCache().resolve(s.replace('/', FILE_SEPARATOR_CHAR))) : null;
        } catch (Exception e) {
            throw new RuntimeException("Could not start script " + s, e);
        }
    }

    private boolean buildScriptProcess(ProcessBuilder pb) {
        final Path script = getScript();
        if (script == null)
            return false;

        if (getAppCache() == null)
            throw new IllegalStateException("Cannot run the startup script " + script + " when the " + ATTR_EXTRACT + " attribute is set to false");

        setJavaHomeEnv(pb, getJavaHome());

        final List<Path> classPath = buildClassPath();
        resolveNativeDependencies();
        pb.environment().put(VAR_CLASSPATH, compileClassPath(classPath));

        ensureExecutable(script);
        pb.command().add(processOutgoingPath(script));
        return true;
    }

    private Path setJavaHomeEnv(ProcessBuilder pb, Path javaHome) {
        if (javaHome == null)
            return null;
        pb.environment().put(VAR_JAVA_HOME, javaHome.toString());
        return javaHome;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Java Process">
    /////////// Java Process ///////////////////////////////////
    private boolean buildJavaProcess(ProcessBuilder pb, List<String> cmdLine, List<String> args) {
        final List<String> command = pb.command();

        command.add(processOutgoingPath(getJavaExecutable()));

        command.addAll(buildJVMArgs(cmdLine));
        command.addAll(compileSystemProperties(buildSystemProperties(cmdLine)));

        addOption(command, "-Xbootclasspath:", compileClassPath(buildBootClassPath(cmdLine)));
        addOption(command, "-Xbootclasspath/p:", compileClassPath(resolve(getAttribute(ATTR_BOOT_CLASS_PATH_P))));
        addOption(command, "-Xbootclasspath/a:", compileClassPath(resolve(getAttribute(ATTR_BOOT_CLASS_PATH_A))));

        command.addAll(compileAgents("-javaagent:", buildAgents(true)));
        command.addAll(compileAgents("-agentpath:", buildAgents(false)));

        final List<Path> classPath = buildClassPath();
        final String mainClass = getMainClass(classPath);

        command.add("-classpath");
        command.add(compileClassPath(handleLongClasspath(classPath, mainClass.length(), command, args)));

        command.add(mainClass);
        return true;
    }

    private List<Path> handleLongClasspath(List<Path> cp, int extra, List<?>... args) {
        if (!isWindows())
            return cp; // why work hard if we know the problem only exists on Windows?

        long len = extra + getStringsLength(cp) + cp.size();
        for (List<?> list : args)
            len += getStringsLength(list) + list.size();

        if (len >= getMaxCommandLineLength()) {
            log(LOG_DEBUG, "Command line length: " + len);
            if (isTrampoline())
                throw new RuntimeException("Command line too long and trampoline requested.");
            final Path pathingJar = addTempFile(createPathingJar(getTempDir(), cp));
            log(LOG_VERBOSE, "Writing classpath: " + cp + " to pathing JAR: " + pathingJar);
            return singletonList(pathingJar);
        } else
            return cp;
    }

    /**
     * Returns the path to the executable that will be used to launch Java.
     * The default implementation uses the {@code capsule.java.cmd} property or the {@code JAVACMD} environment variable,
     * and if not set, returns the value of {@code getJavaExecutable(getJavaHome())}.
     */
    protected Path getJavaExecutable() {
        return (_ct = getCallTarget(Capsule.class)) != null ? _ct.getJavaExecutable() : getJavaExecutable0();
    }

    private Path getJavaExecutable0() {
        String javaCmd = emptyToNull(getProperty(PROP_CAPSULE_JAVA_CMD));
        if (javaCmd != null)
            return path(javaCmd);

        return getJavaExecutable(getJavaHome());
    }

    /**
     * Finds the path to the executable that will be used to launch Java within the given {@code javaHome}.
     */
    protected static final Path getJavaExecutable(Path javaHome) {
        return getJavaExecutable0(javaHome);
    }

    private static List<String> compileSystemProperties(Map<String, String> ps) {
        final List<String> command = new ArrayList<String>();
        for (Map.Entry<String, String> entry : ps.entrySet())
            command.add("-D" + entry.getKey() + (entry.getValue() != null && !entry.getValue().isEmpty() ? "=" + entry.getValue() : ""));
        return command;
    }

    private String compileClassPath(List<Path> cp) {
        if (isEmpty(cp))
            return null;
        return join(processOutgoingPath(cp), PATH_SEPARATOR);
    }

    private List<String> compileAgents(String clo, Map<Path, String> agents) {
        final List<String> command = new ArrayList<>();
        for (Map.Entry<Path, String> agent : nullToEmpty(agents).entrySet())
            command.add(clo + processOutgoingPath(agent.getKey()) + (agent.getValue().isEmpty() ? "" : ("=" + agent.getValue())));
        return command;
    }

    private static void addOption(List<String> cmdLine, String prefix, String value) {
        if (value == null)
            return;
        cmdLine.add(prefix + value);
    }

    private List<Path> buildClassPath() {
        final long start = clock();
        final List<Path> classPath = new ArrayList<Path>();

        // the capsule jar
        if (!isWrapperOfNonCapsule()) {
            if (Boolean.parseBoolean(getAttribute(ATTR_CAPSULE_IN_CLASS_PATH)))
                classPath.add(getJarFile());
            else if (getAppCache() == null)
                throw new IllegalStateException("Cannot set the " + ATTR_CAPSULE_IN_CLASS_PATH + " attribute to false when the " + ATTR_EXTRACT + " attribute is also set to false");
        }

        if (hasAttribute(ATTR_APP_ARTIFACT)) {
            if (isGlob(getAttribute(ATTR_APP_ARTIFACT)))
                throw new IllegalArgumentException("Glob pattern not allowed in " + ATTR_APP_ARTIFACT + " attribute.");
            final List<Path> app = isWrapperOfNonCapsule()
                    ? singletonList(toAbsolutePath(path(getAttribute(ATTR_APP_ARTIFACT))))
                    : resolve(getAttribute(ATTR_APP_ARTIFACT));
            classPath.addAll(app);
            final Path jar = app.get(0);
            final Manifest man = getManifest(jar);
            for (String e : nullToEmpty(parse(man.getMainAttributes().getValue(ATTR_CLASS_PATH)))) {
                Path p;
                try {
                    p = path(new URL(e).toURI());
                } catch (MalformedURLException | URISyntaxException ex) {
                    p = jar.getParent().resolve(path(e.replace('/', FILE_SEPARATOR_CHAR)));
                }
                if (!classPath.contains(p))
                    classPath.add(isWrapperOfNonCapsule() ? toAbsolutePath(p) : sanitize(p));
            }
        }

        if (hasAttribute(ATTR_APP_CLASS_PATH)) {
            for (String sp : getAttribute(ATTR_APP_CLASS_PATH))
                addAllIfAbsent(classPath, resolve(sp));
        }

        if (getAppCache() != null)
            addAllIfAbsent(classPath, nullToEmpty(getDefaultCacheClassPath()));

        classPath.addAll(resolve(getAttribute(ATTR_DEPENDENCIES)));

        time("buildClassPath", start);
        return classPath;
    }

    private List<Path> getDefaultCacheClassPath() {
        final List<Path> cp = new ArrayList<Path>(listDir(getAppCache(), "*.jar", true));
        cp.add(0, getAppCache());
        return cp;
    }

    /**
     * Compiles and returns the application's boot classpath as a list of paths.
     */
    private List<Path> buildBootClassPath(List<String> cmdLine) {
        String option = null;
        for (String o : cmdLine) {
            if (o.startsWith("-Xbootclasspath:"))
                option = o.substring("-Xbootclasspath:".length());
        }
        return option != null ? toPath(asList(option.split(PATH_SEPARATOR))) : resolve(getAttribute(ATTR_BOOT_CLASS_PATH));
    }

    private Map<String, String> buildSystemProperties(List<String> cmdLine) {
        final Map<String, String> systemProperties = buildSystemProperties();

        // command line overrides everything
        for (String option : cmdLine) {
            if (option.startsWith("-D") && !isCapsuleOption(option.substring(2)))
                addSystemProperty(option.substring(2), systemProperties);
        }

        return systemProperties;
    }

    private Map<String, String> buildSystemProperties() {
        final Map<String, String> systemProperties = new HashMap<String, String>();

        // attribute
        for (Map.Entry<String, String> pv : getAttribute(ATTR_SYSTEM_PROPERTIES).entrySet())
            systemProperties.put(pv.getKey(), pv.getValue());

        // library path
        final List<Path> libraryPath = buildNativeLibraryPath();
        systemProperties.put(PROP_JAVA_LIBRARY_PATH, compileClassPath(libraryPath));

        // security manager
        if (hasAttribute(ATTR_SECURITY_POLICY) || hasAttribute(ATTR_SECURITY_POLICY_A)) {
            systemProperties.put(PROP_JAVA_SECURITY_MANAGER, "");
            if (hasAttribute(ATTR_SECURITY_POLICY_A))
                systemProperties.put(PROP_JAVA_SECURITY_POLICY, toJarUrl(getAttribute(ATTR_SECURITY_POLICY_A)));
            if (hasAttribute(ATTR_SECURITY_POLICY))
                systemProperties.put(PROP_JAVA_SECURITY_POLICY, "=" + toJarUrl(getAttribute(ATTR_SECURITY_POLICY)));
        }
        if (hasAttribute(ATTR_SECURITY_MANAGER))
            systemProperties.put(PROP_JAVA_SECURITY_MANAGER, getAttribute(ATTR_SECURITY_MANAGER));

        // Capsule properties
        if (getAppId() != null) {
            if (getAppCache() != null)
                systemProperties.put(PROP_CAPSULE_DIR, processOutgoingPath(getAppCache()));
            systemProperties.put(PROP_CAPSULE_JAR, processOutgoingPath(getJarFile()));
            systemProperties.put(PROP_CAPSULE_APP, getAppId());
        }

        return systemProperties;
    }

    private static void addSystemProperty(String p, Map<String, String> ps) {
        try {
            String name = getBefore(p, '=');
            String value = getAfter(p, '=');
            ps.put(name, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Illegal system property definition: " + p);
        }
    }

    //<editor-fold desc="Native Dependencies">
    /////////// Native Dependencies ///////////////////////////////////
    private List<Path> buildNativeLibraryPath() {
        final List<Path> libraryPath = new ArrayList<Path>(getPlatformNativeLibraryPath());

        resolveNativeDependencies();
        if (hasAttribute(ATTR_LIBRARY_PATH_P) || hasAttribute(ATTR_LIBRARY_PATH_A)) {
            libraryPath.addAll(0, sanitize(resolve(verifyAppCache(), getAttribute(ATTR_LIBRARY_PATH_P))));
            libraryPath.addAll(sanitize(resolve(verifyAppCache(), getAttribute(ATTR_LIBRARY_PATH_A))));
        }
        if (getAppCache() != null)
            libraryPath.add(getAppCache());
        return libraryPath;
    }

    /**
     * Returns the default native library path for the Java platform the application uses.
     */
    protected List<Path> getPlatformNativeLibraryPath() {
        return (_ct = getCallTarget(Capsule.class)) != null ? _ct.getPlatformNativeLibraryPath() : getPlatformNativeLibraryPath0();
    }

    private List<Path> getPlatformNativeLibraryPath0() {
        // WARNING: this assumes the platform running the app (say a different Java home), has the same java.library.path.
        return toPath(asList(getProperty(PROP_JAVA_LIBRARY_PATH).split(PATH_SEPARATOR)));
    }

    private void resolveNativeDependencies() {
        final Map<String, String> depsAndRename = getAttribute(ATTR_NATIVE_DEPENDENCIES);
        if (depsAndRename == null || depsAndRename.isEmpty())
            return;
        verifyAppCache();

        final List<String> deps = new ArrayList<String>(depsAndRename.keySet());
        log(LOG_VERBOSE, "Resolving native libs " + deps);
        final List<Path> resolved = nullToEmpty(resolveDependencies(deps, getNativeLibExtension()));
        if (resolved.size() != deps.size())
            throw new RuntimeException("One of the native artifacts " + deps + " reolved to more than a single file or to none");

        if (!cacheUpToDate) {
            log(LOG_DEBUG, "Copying native libs to " + getWritableAppCache());
            try {
                int i = 0;
                for (Map.Entry<String, String> e : depsAndRename.entrySet()) {
                    final Path lib = resolved.get(i);
                    final String rename = emptyToNull(e.getValue());
                    Files.copy(lib, sanitize(getWritableAppCache().resolve(rename != null ? rename : lib.getFileName().toString())));
                    i++;
                }
            } catch (IOException e) {
                throw new RuntimeException("Exception while copying native libs", e);
            }
        }
    }
    //</editor-fold>

    private List<String> buildJVMArgs(List<String> cmdLine) {
        final Map<String, String> jvmArgs = new LinkedHashMap<String, String>();

        for (String option : buildJVMArgs())
            addJvmArg(option, jvmArgs);

        for (String option : nullToEmpty(Capsule.split(getProperty(PROP_JVM_ARGS), " ")))
            addJvmArg(option, jvmArgs);

        // command line overrides everything
        for (String option : cmdLine) {
            if (!option.startsWith("-D") && !option.startsWith("-Xbootclasspath:"))
                addJvmArg(option, jvmArgs);
        }
        return new ArrayList<String>(jvmArgs.values());
    }

    private List<String> buildJVMArgs() {
        final Map<String, String> jvmArgs = new LinkedHashMap<String, String>();

        for (String a : getAttribute(ATTR_JVM_ARGS)) {
            a = a.trim();
            if (!a.isEmpty() && !a.startsWith("-Xbootclasspath:") && !a.startsWith("-javaagent:"))
                addJvmArg(expand(a), jvmArgs);
        }

        return new ArrayList<String>(jvmArgs.values());
    }

    private static void addJvmArg(String a, Map<String, String> args) {
        args.put(getJvmArgKey(a), a);
    }

    private static String getJvmArgKey(String a) {
        if (a.equals("-client") || a.equals("-server"))
            return "compiler";
        if (a.equals("-enablesystemassertions") || a.equals("-esa")
                || a.equals("-disablesystemassertions") || a.equals("-dsa"))
            return "systemassertions";
        if (a.equals("-jre-restrict-search") || a.equals("-no-jre-restrict-search"))
            return "-jre-restrict-search";
        if (a.startsWith("-Xloggc:"))
            return "-Xloggc";
        if (a.startsWith("-Xss"))
            return "-Xss";
        if (a.startsWith("-Xmx"))
            return "-Xmx";
        if (a.startsWith("-Xms"))
            return "-Xms";
        if (a.startsWith("-XX:+") || a.startsWith("-XX:-"))
            return "-XX:" + a.substring("-XX:+".length());
        if (a.contains("="))
            return a.substring(0, a.indexOf('='));
        return a;
    }

    private Map<Path, String> buildAgents(boolean java) {
        final long start = clock();
        final Map<String, String> agents0 = getAttribute(java ? ATTR_JAVA_AGENTS : ATTR_NATIVE_AGENTS);
        final Map<Path, String> agents = new LinkedHashMap<>(agents0.size());
        for (Map.Entry<String, String> agent : agents0.entrySet()) {
            final String agentName = agent.getKey();
            final String agentOptions = agent.getValue();
            try {
                final Path agentPath = first(resolve(agentName + (java ? "" : ("." + getNativeLibExtension()))));
                agents.put(agentPath, ((agentOptions != null && !agentOptions.isEmpty()) ? agentOptions : ""));
            } catch (IllegalStateException e) {
                if (getAppCache() == null && isThrownByCapsule(e))
                    throw new RuntimeException("Cannot run the embedded agent " + agentName + " when the " + ATTR_EXTRACT + " attribute is set to false", e);
                throw e;
            }
        }
        time("buildAgents (" + (java ? "java" : "native") + ")", start);
        return emptyToNull(agents);
    }

    private String getMainClass(List<Path> classPath) {
        String mainClass = getAttribute(ATTR_APP_CLASS);
        if (mainClass == null && hasAttribute(ATTR_APP_ARTIFACT))
            mainClass = getMainClass(getAppArtifactJarFromClasspath(classPath));
        if (mainClass == null)
            throw new RuntimeException("Jar " + classPath.get(0).toAbsolutePath() + " does not have a main class defined in the manifest.");
        return mainClass;
    }

    private Path getAppArtifactJarFromClasspath(List<Path> classPath) {
        return classPath.get(0).equals(getJarFile()) ? classPath.get(1) : classPath.get(0);
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Get Java Home">
    /////////// Get Java Home ///////////////////////////////////
    /**
     * The path to the Java installation this capsule's app will use.
     */
    protected final Path getJavaHome() {
        if (oc.javaHome == null) {
            final Path jhome = chooseJavaHome();
            oc.javaHome = jhome != null ? jhome : Paths.get(getProperty(PROP_JAVA_HOME));
            log(LOG_VERBOSE, "Using JVM: " + oc.javaHome);
        }
        return oc.javaHome;
    }

    /**
     * Chooses which Java installation to use for running the app.
     *
     * @return the path of the Java installation to use for launching the app, or {@code null} if the current JVM is to be used.
     */
    protected Path chooseJavaHome() {
        return (_ct = getCallTarget(Capsule.class)) != null ? _ct.chooseJavaHome() : chooseJavaHome0();
    }

    private Path chooseJavaHome0() {
        final long start = clock();
        final String propJHome = emptyToNull(getProperty(PROP_CAPSULE_JAVA_HOME));
        Path jhome = null;
        if (!"current".equals(propJHome)) {
            jhome = propJHome != null ? Paths.get(propJHome) : null;
            if (jhome == null && !isMatchingJavaVersion(getProperty(PROP_JAVA_VERSION), isJDK(Paths.get(getProperty(PROP_JAVA_HOME))))) {
                final boolean jdk = getAttribute(ATTR_JDK_REQUIRED);

                jhome = findJavaHome(jdk);
                if (isLogging(LOG_VERBOSE))
                    log(LOG_VERBOSE, "Finding JVM: " + ((System.nanoTime() - start) / 1_000_000) + "ms");

                if (jhome == null) {
                    throw new RuntimeException("Could not find Java installation for requested version "
                            + '[' + "Min. Java version: " + getAttribute(ATTR_MIN_JAVA_VERSION)
                            + " JavaVersion: " + getAttribute(ATTR_JAVA_VERSION)
                            + " Min. update version: " + getAttribute(ATTR_MIN_UPDATE_VERSION) + ']'
                            + " (JDK required: " + jdk + ")"
                            + ". You can override the used Java version with the -D" + PROP_CAPSULE_JAVA_HOME + " flag.");
                }
            }
        }
        time("chooseJavaHome", start);
        return jhome != null ? jhome.toAbsolutePath() : jhome;
    }

    private Path findJavaHome(boolean jdk) {
        Map<String, List<Path>> homes = nullToEmpty(getJavaHomes());
        Path best = null;
        String bestVersion = null;
        for (Map.Entry<String, List<Path>> e : homes.entrySet()) {
            for (Path home : e.getValue()) {
                final String v = e.getKey();
                log(LOG_DEBUG, "Trying JVM: " + e.getValue() + " (version " + v + ")");
                if (isMatchingJavaVersion(v, isJDK(home))) {
                    log(LOG_DEBUG, "JVM " + e.getValue() + " (version " + v + ") matches");
                    if (bestVersion == null || compareVersions(v, bestVersion) > 0) {
                        log(LOG_DEBUG, "JVM " + e.getValue() + " (version " + v + ") is best so far");
                        bestVersion = v;
                        best = home;
                    }
                }
            }
        }
        return best;
    }

    private boolean isMatchingJavaVersion(String javaVersion, boolean jdk) {
        final boolean jdkRequired = getAttribute(ATTR_JDK_REQUIRED);

        if (jdkRequired && !jdk) {
            log(LOG_DEBUG, "Java version " + javaVersion + " fails to match because JDK required and this is not a JDK");
            return false;
        }
        if (hasAttribute(ATTR_MIN_JAVA_VERSION) && compareVersions(javaVersion, getAttribute(ATTR_MIN_JAVA_VERSION)) < 0) {
            log(LOG_DEBUG, "Java version " + javaVersion + " fails to match due to " + ATTR_MIN_JAVA_VERSION + ": " + getAttribute(ATTR_MIN_JAVA_VERSION));
            return false;
        }
        if (hasAttribute(ATTR_JAVA_VERSION) && compareVersions(javaVersion, shortJavaVersion(getAttribute(ATTR_JAVA_VERSION)), 3) > 0) {
            log(LOG_DEBUG, "Java version " + javaVersion + " fails to match due to " + name(ATTR_JAVA_VERSION) + ": " + getAttribute(ATTR_JAVA_VERSION));
            return false;
        }
        if (getMinUpdateFor(javaVersion) > parseJavaVersion(javaVersion)[3]) {
            log(LOG_DEBUG, "Java version " + javaVersion + " fails to match due to " + name(ATTR_MIN_UPDATE_VERSION) + ": " + getAttribute(ATTR_MIN_UPDATE_VERSION) + " (" + getMinUpdateFor(javaVersion) + ")");
            return false;
        }
        log(LOG_DEBUG, "Java version " + javaVersion + " matches");
        return true;
    }

    private int getMinUpdateFor(String version) {
        final Map<String, String> m = getAttribute(ATTR_MIN_UPDATE_VERSION);
        final int[] ver = parseJavaVersion(version);
        for (Map.Entry<String, String> entry : m.entrySet()) {
            if (equals(ver, toInt(shortJavaVersion(entry.getKey()).split(SEPARATOR_DOT)), 3))
                return Integer.parseInt(entry.getValue());
        }
        return 0;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Dependency Resolution">
    /////////// Dependency Resolution ///////////////////////////////////
    /**
     * @deprecated marked deprecated to exclude from javadoc.
     */
    protected List<Path> resolveDependencies(List<String> coords, String type) {
        final long start = clock();
        final Capsule ct;
        final List<Path> res = (ct = unsafe(getCallTarget(Capsule.class))) != null ? ct.resolveDependencies(coords, type) : resolveDependencies0(coords, type);
        if (ct == cc) {
            time("resolveDependencies" + coords + ", " + type, start);
            log(LOG_DEBUG, "resolveDependencies " + coords + ", " + type + " -> " + res);
        }
        return res;
    }

    private List<Path> resolveDependencies0(List<String> coords, String type) {
        if (coords == null)
            return null;

        final List<Path> res = new ArrayList<>();
        for (String dep : coords)
            res.addAll(nullToEmpty(resolveDependency(dep, type)));

        return emptyToNull(res);
    }

    /**
     * @deprecated marked deprecated to exclude from javadoc.
     */
    protected List<Path> resolveDependency(String coords, String type) {
        final long start = clock();
        final Capsule ct;
        final List<Path> res = (ct = unsafe(getCallTarget(Capsule.class))) != null ? ct.resolveDependency(coords, type) : resolveDependency0(coords, type);
        if (ct == cc) {
            time("resolveDependency " + coords + ", " + type, start);
            log(LOG_DEBUG, "resolveDependency " + coords + ", " + type + " -> " + res);
        }
        return res;
    }

    private List<Path> resolveDependency0(String coords, String type) {
        if (coords == null)
            return null;
        final Path file = dependencyToLocalJar(verifyAppCache(), coords, type);
        return file != null ? singletonList(file) : null;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Attributes">
    /////////// Attributes ///////////////////////////////////
    @SuppressWarnings("unchecked")
    private <T> T attribute0(Entry<String, T> attr) {
        if (ATTR_APP_ID == attr) {
            String id = attribute00(ATTR_APP_ID);
            if (id == null && getManifestAttribute(ATTR_IMPLEMENTATION_TITLE) != null)
                id = getManifestAttribute(ATTR_IMPLEMENTATION_TITLE);
            if (id == null && hasAttribute(ATTR_APP_ARTIFACT) && isDependency(getAttribute(ATTR_APP_ARTIFACT)))
                id = getAppArtifactId(getAttribute(ATTR_APP_ARTIFACT));
            return (T) id;
        }

        if (ATTR_APP_NAME == attr) {
            String name = attribute00(ATTR_APP_NAME);
            if (name == null)
                name = getManifestAttribute(ATTR_IMPLEMENTATION_TITLE);
            return (T) name;
        }

        if (ATTR_APP_VERSION == attr) {
            String ver = attribute00(ATTR_APP_VERSION);
            if (ver == null && getManifestAttribute(ATTR_IMPLEMENTATION_VERSION) != null)
                ver = getManifestAttribute(ATTR_IMPLEMENTATION_VERSION);
            if (ver == null && hasAttribute(ATTR_APP_ARTIFACT) && isDependency(getAttribute(ATTR_APP_ARTIFACT)))
                ver = getAppArtifactVersion(getAttribute(ATTR_APP_ARTIFACT));
            return (T) ver;
        }
        return attribute00(attr);
    }

    /*
     * The methods in this section are the only ones accessing the manifest. Therefore other means of
     * setting attributes can be added by changing these methods alone.
     */
    /**
     * Registers a manifest attribute. Must be called during the caplet's static initialization.
     *
     * @param attrName     the attribute's name
     * @param type         the attribute's type, obtained by calling one (or a combination) of the "type" methods:
     *                     {@link #T_STRING() T_STRING}, {@link #T_BOOL() T_BOOL}, {@link #T_LONG() T_LONG}, {@link #T_DOUBLE() T_DOUBLE},
     *                     {@link #T_LIST(Object) T_LIST}, {@link #T_MAP(Object, Object) T_MAP}, {@link #T_SET(Object) T_SET}
     * @param defaultValue the attribute's default value, or {@code null} for none; a {@code null} value for collection or map types will be transformed into the type's empty value (i.e. empty list, empty map, etc.)
     * @param allowModal   whether the attribute is modal (i.e. can be specified per mode); if {@code false}, then the attribute is only allowed in the manifest's main section.
     * @param description  a description of the attribute
     * @return the attribute's name
     */
    protected static final <T> Entry<String, T> ATTRIBUTE(String attrName, T type, T defaultValue, boolean allowModal, String description) {
        if (!isValidType(type))
            throw new IllegalArgumentException("Type " + type + " is not supported for attributes");
        final Object[] conf = new Object[]{type, defaultValue, allowModal, description};
        final Object[] old = ATTRIBS.get(attrName);
        if (old != null) {
            if (asList(conf).subList(0, conf.length - 1).equals(asList(old).subList(0, conf.length - 1))) // don't compare description
                throw new IllegalStateException("Attribute " + attrName + " has a conflicting registration: " + Arrays.toString(old));
        }
        ATTRIBS.put(attrName, conf);
        return new AbstractMap.SimpleImmutableEntry<String, T>(attrName, null);
    }

    /**
     * Returns the value of the given manifest attribute with consideration to the capsule's mode.
     * If the attribute is not defined, its default value will be returned
     * (if set with {@link #ATTRIBUTE(String, Object, Object, boolean, String) ATTRIBUTE()}).
     * <p>
     * Note that caplets may manipulate the value this method returns by overriding {@link #attribute(Map.Entry) }.
     *
     * @param attr the attribute
     * @return the value of the attribute.
     */
    protected final <T> T getAttribute(Entry<String, T> attr) {
        if (name(ATTR_CAPLETS).equals(name(attr)))
            return attribute0(attr);
        try {
            final T value = cc.attribute(attr);
            setContext("attribute", name(attr), value);
            return value;
        } catch (Exception e) {
            throw new RuntimeException("Exception while getting attribute " + name(attr), e);
        }
    }

    /**
     * Returns an attribute's name.
     */
    protected final String name(Entry<String, ?> attribute) {
        return attribute.getKey();
    }

    private static boolean isLegalModeName(String name) {
        return !name.contains("/") && !name.endsWith(".class") && !name.endsWith(".jar")
                && !isJavaVersionSpecific(name) && !isOsSpecific(name);
    }

    private void validateManifest(Manifest manifest) {
        if (manifest.getMainAttributes().getValue(ATTR_CLASS_PATH) != null)
            throw new IllegalStateException("Capsule manifest contains a " + ATTR_CLASS_PATH + " attribute."
                    + " Use " + ATTR_APP_CLASS_PATH + " and/or " + ATTR_DEPENDENCIES + " instead.");
        validateNonModalAttributes(manifest);

        if (!hasAttribute(ATTR_APP_NAME) && hasModalAttribute(ATTR_APP_ARTIFACT))
            throw new IllegalArgumentException("App ID-related attribute " + ATTR_APP_ARTIFACT + " is defined in a modal section of the manifest. "
                    + " In this case, you must add the " + ATTR_APP_NAME + " attribute to the manifest's main section.");

        // validate section case-insensitivity
        final Set<String> sectionsLowercase = new HashSet<>();
        for (String section : manifest.getEntries().keySet()) {
            if (!sectionsLowercase.add(section.toLowerCase()))
                throw new IllegalArgumentException("Manifest in JAR " + jarFile + " contains a case-insensitive duplicate of section " + section);
        }
    }

    private void validateNonModalAttributes(Manifest manifest) {
        for (Map.Entry<String, Attributes> entry : manifest.getEntries().entrySet()) {
            for (Object attr : entry.getValue().keySet()) {
                if (!allowsModal(attr.toString()))
                    throw new IllegalStateException("Manifest section " + entry.getKey() + " contains non-modal attribute " + attr);
            }
        }
    }

    private boolean hasModalAttribute(Entry<String, ?> attr) {
        final Attributes.Name key = new Attributes.Name(name(attr));
        for (Map.Entry<String, Attributes> entry : oc.manifest.getEntries().entrySet()) {
            if (entry.getValue().containsKey(key))
                return true;
        }
        return false;
    }

    private boolean hasMode(String mode) {
        if (!isLegalModeName(mode))
            throw new IllegalArgumentException(mode + " is an illegal mode name");
        if (oc.manifest.getAttributes(mode) != null)
            return true;
        return false;
    }

    /**
     * Returns the names of all modes defined in this capsule's manifest.
     */
    protected final Set<String> getModes() {
        final Set<String> modes = new HashSet<>();
        for (Map.Entry<String, Attributes> entry : oc.manifest.getEntries().entrySet()) {
            if (isLegalModeName(entry.getKey()) && !isDigest(entry.getValue()))
                modes.add(entry.getKey());
        }
        return unmodifiableSet(modes);
    }

    @SuppressWarnings("unchecked")
    private String getManifestAttribute(String attr) {
        return oc.manifest.getMainAttributes().getValue(attr);
    }

    /**
     * Returns the description of the given mode.
     */
    protected final String getModeDescription(String mode) {
        if (!isLegalModeName(mode))
            throw new IllegalArgumentException(mode + " is an illegal mode name");
        if (oc.manifest != null && oc.manifest.getAttributes(mode) != null)
            return oc.manifest.getAttributes(mode).getValue(name(ATTR_MODE_DESC));
        return null;
    }

    private static boolean isDigest(Attributes attrs) {
        for (Object name : attrs.keySet()) {
            if (!name.toString().toLowerCase().endsWith("-digest") && !name.toString().equalsIgnoreCase("Magic"))
                return false;
        }
        return true;
    }

    private static boolean isOsSpecific(String section) {
        section = section.toLowerCase();
        if (PLATFORMS.contains(section))
            return true;
        for (String os : PLATFORMS) {
            if (section.endsWith("-" + os))
                return true;
        }
        return false;
    }

    private static final Pattern PAT_JAVA_SPECIFIC_SECTION = Pattern.compile("\\A(.+-|)java-[0-9]+\\z");

    private static boolean isJavaVersionSpecific(String section) {
        return PAT_JAVA_SPECIFIC_SECTION.matcher(section.toLowerCase()).find();
    }

    /**
     * CAPLET OVERRIDE ONLY: Returns the value of the given capsule attribute with consideration to the capsule's mode.
     * Caplets may override this method to manipulate capsule attributes. This method must not be called directly except
     * as {@code super.attribute(attr)} calls in the caplet's implementation of this method.
     * <p>
     * The default implementation parses and returns the relevant manifest attribute or its default value if undefined.
     *
     * @param attr the attribute
     * @return the value of the attribute.
     * @see #getAttribute(Map.Entry)
     */
    protected <T> T attribute(Entry<String, T> attr) {
        return sup != null ? sup.attribute(attr) : attribute0(attr);
    }

    @SuppressWarnings("unchecked")
    private <T> T attribute00(Entry<String, T> attr) {
        final Object[] conf = ATTRIBS.get(name(attr));
//        if (conf == null)
//            throw new IllegalArgumentException("Attribute " + attr.getKey() + " has not been registered with ATTRIBUTE");
        final T type = (T) (conf != null ? conf[ATTRIB_TYPE] : T_STRING());
        T value = oc.getAttribute0(name(attr), type);
        if (isEmpty(value))
            value = defaultValue(type, (T) (conf != null ? conf[ATTRIB_DEFAULT] : null));
        setContext("attribute", attr.getKey(), value);
        return value;
    }

    private <T> T parseAttribute(String attr, T type, String s) {
        try {
            return parse(expand(s), type);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Error parsing attribute " + attr + ". Expected " + typeString(type) + " but was: " + s, e);
        }
    }

    private <T> T getAttribute0(String attr, T type) {
        T value = null;
        final String majorJavaVersion = majorJavaVersion(getJavaVersion(oc.javaHome));
        if (manifest != null) {
            value = merge(value, parseAttribute(attr, type, getAttributes(manifest, null, null).getValue(attr)));
            if (majorJavaVersion != null)
                value = merge(value, parseAttribute(attr, type, getAttributes(manifest, null, "java-" + majorJavaVersion).getValue(attr)));
            value = merge(value, parseAttribute(attr, type, getPlatformAttribute(null, attr)));
            if (getMode() != null && allowsModal(attr)) {
                value = merge(value, parseAttribute(attr, type, getAttributes(manifest, mode, null).getValue(attr)));
                if (majorJavaVersion != null)
                    value = merge(value, parseAttribute(attr, type, getAttributes(manifest, mode, "java-" + majorJavaVersion).getValue(attr)));
                value = merge(value, parseAttribute(attr, type, getPlatformAttribute(getMode(), attr)));
            }
            setContext("attribute of " + jarFile, attr, value);
        }
        return value;
    }

    private String getPlatformAttribute(String mode, String attr) {
        String value = null;
        if (value == null)
            value = getAttributes(manifest, mode, PLATFORM).getValue(attr);
        if (value == null && isUnix())
            value = getAttributes(manifest, mode, OS_UNIX).getValue(attr);
        if (value == null && (isUnix() || isMac()))
            value = getAttributes(manifest, mode, OS_POSIX).getValue(attr);
        return value;
    }

    private static Attributes getAttributes(Manifest manifest, String mode, String platform) {
        if (emptyToNull(mode) == null && emptyToNull(platform) == null)
            return manifest.getMainAttributes();
        if (emptyToNull(mode) == null)
            return getAttributes(manifest, platform);
        if (emptyToNull(platform) == null)
            return getAttributes(manifest, mode);
        return getAttributes(manifest, mode + "-" + platform);
    }

    /**
     * Tests whether the given attribute is found in the manifest.
     *
     * @param attr the attribute
     */
    protected final boolean hasAttribute(Entry<String, ?> attr) {
        return !isEmpty(getAttribute(attr));
    }

    private boolean allowsModal(String attr) {
        final Object[] vals = ATTRIBS.get(attr);
        return vals != null ? (Boolean) vals[ATTRIB_MODAL] : true;
    }

    //<editor-fold defaultstate="collapsed" desc="Attribute Types and Parsing">
    /////////// Attribute Types and Parsing ///////////////////////////////////
    /**
     * Represents the attribute type {@code  String}
     */
    protected static final String T_STRING() {
        return "";
    }

    /**
     * Represents the attribute type {@code Boolean}
     */
    protected static final Boolean T_BOOL() {
        return false;
    }

    /**
     * Represents the attribute type {@code Long}
     */
    protected static final Long T_LONG() {
        return 0L;
    }

    /**
     * Represents the attribute type {@code Double}
     */
    protected static final Double T_DOUBLE() {
        return 0.0;
    }

    /**
     * A {@code List} of type {@code type}
     *
     * @param type One of {@link #T_STRING() T_STRING}, {@link #T_BOOL() T_BOOL}, {@link #T_LONG() T_LONG}, {@link #T_DOUBLE() T_DOUBLE}
     */
    protected static final <E> List<E> T_LIST(E type) {
        return singletonList(type);
    }

    /**
     * A {@code Set} of type {@code type}
     *
     * @param type One of {@link #T_STRING() T_STRING}, {@link #T_BOOL() T_BOOL}, {@link #T_LONG() T_LONG}, {@link #T_DOUBLE() T_DOUBLE}
     */
    protected static final <E> Set<E> T_SET(E type) {
        return singleton(type);
    }

    /**
     * A {@code Map} from {@code String} to type {@code type}
     *
     * @param type         One of {@link #T_STRING() T_STRING}, {@link #T_BOOL() T_BOOL}, {@link #T_LONG() T_LONG}, {@link #T_DOUBLE() T_DOUBLE}
     * @param defaultValue The default value for a key without a value in the attribute string, or {@code null} if all keys must explicitly specify a value.
     */
    @SuppressWarnings("unchecked")
    protected static final <E> Map<String, E> T_MAP(E type, E defaultValue) {
        return (Map<String, E>) (defaultValue != null ? singletonMap(T_STRING(), promote(defaultValue, type)) : singletonMap(null, type));
    }

    @SuppressWarnings("unchecked")
    private static boolean isValidType(Object type) {
        if (type == null)
            return false;
        Object etype = null;
        if (type instanceof Collection) {
            if (!(type instanceof List || type instanceof Set))
                return false;
            etype = ((Collection<?>) type).iterator().next();
        } else if (type instanceof Map) {
            final Map.Entry<String, ?> desc = ((Map<String, ?>) type).entrySet().iterator().next();
            etype = desc.getValue();
        }

        if (etype != null) {
            if (etype instanceof Collection || etype instanceof Map)
                return false;
            return isValidType(etype);
        } else
            return ((Collection<Class>) (Object) asList(String.class, Boolean.class, Long.class, Double.class)).contains(type.getClass());
    }

    private static String typeString(Object type) {
        if (type instanceof Collection) {
            final Object etype = ((Collection<?>) type).iterator().next();
            final String collType = type instanceof Set ? "Set" : "List";
            return collType + " of " + typeString(etype) + " in the form \"v1 v2 ...\"";
        } else if (type instanceof Map) {
            final Map.Entry<String, ?> desc = ((Map<String, ?>) type).entrySet().iterator().next();
            final Object etype = desc.getValue();
            return "map of String to " + typeString(etype) + " in the form \"k1=v1 k2=v2 ...\"";
        } else
            return type.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private <T> T defaultValue(T type, T d) {
        if (d == null) {
            if (type instanceof List)
                return (T) emptyList();
            if (type instanceof Set)
                return (T) emptySet();
            if (type instanceof Map)
                return (T) emptyMap();
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    // visible for testing
    static <T> T parse(String s, T type) {
        if (type instanceof Collection) {
            final Object etype = ((Collection<?>) type).iterator().next();
            final List<String> slist = parse(s);
            if (type instanceof List && etype instanceof String)
                return (T) slist;
            final Collection<Object> coll = type instanceof Set ? new HashSet<>() : new ArrayList<>();
            for (String se : slist)
                coll.add(parse(se, etype));
            return (T) coll;
        } else if (type instanceof Map) {
            final Map.Entry<String, ?> desc = ((Map<String, ?>) type).entrySet().iterator().next();
            final Object etype = desc.getValue();
            final Object defaultValue = desc.getKey() != null ? desc.getValue() : null;
            final String sdefaultValue = defaultValue != null ? defaultValue.toString() : null;
            final Map<String, String> smap = parse(s, sdefaultValue);
            if (etype instanceof String)
                return (T) smap;
            final Map<String, Object> map = new HashMap<>();
            for (Map.Entry<String, String> se : smap.entrySet())
                map.put(se.getKey(), parsePrimitive(se.getValue(), etype));
            return (T) map;
        } else
            return parsePrimitive(s, type);
    }

    @SuppressWarnings("unchecked")
    private static <T> T parsePrimitive(String s, T type) {
        if (s == null)
            return null;
        if (type instanceof String)
            return (T) s;
        if (type instanceof Boolean)
            return (T) (Boolean) Boolean.parseBoolean(s);
        if (type instanceof Long)
            return (T) (Long) Long.parseLong(s);
        if (type instanceof Double)
            return (T) (Double) Double.parseDouble(s);
        throw new IllegalArgumentException("Unsupported primitive attribute type: " + type.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    private static <T> T promote(Object x, T type) {
        if (!(x instanceof Number && type instanceof Number))
            return (T) x;

        if (x instanceof Integer) {
            if (type instanceof Long)
                x = Long.valueOf((Integer) x);
            else if (type instanceof Double)
                x = Double.valueOf((Integer) x);
        }
        return (T) x;
    }

    private static List<String> parse(String value) {
        return split(value, "\\s+");
    }

    private static Map<String, String> parse(String value, String defaultValue) {
        return split(value, '=', "\\s+", defaultValue);
    }
    //</editor-fold>

    private static final Attributes EMPTY_ATTRIBUTES = new Attributes();

    private static Attributes getAttributes(Manifest manifest, String name) {
//        Attributes as =  = manifest.getAttributes(name);
//        return as != null ? as : EMPTY_ATTRIBUTES;
        for (Map.Entry<String, Attributes> entry : manifest.getEntries().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name))
                return entry.getValue();
        }
        return EMPTY_ATTRIBUTES;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Dependency Utils">
    /////////// Dependency Utils ///////////////////////////////////
    private static boolean isDependency(String lib) {
        return lib.contains(":") && !lib.contains(":\\");
    }

    private static Path dependencyToLocalJar(Path root, String dep, String type) {
        final String[] coords = dep.split(":");
        final String group = coords[0];
        final String artifact = coords[1];
        final String version = coords.length > 2 ? (coords[2] + (coords.length > 3 ? "-" + coords[3] : "")) : null;
        final String filename = artifact + (version != null && !version.isEmpty() ? '-' + version : "") + "." + type;
        Path p;
        if (group != null && !group.isEmpty()) {
            p = root.resolve("lib").resolve(group).resolve(filename);
            if (Files.isRegularFile(p))
                return p;
            p = root.resolve("lib").resolve(group + '-' + filename);
            if (Files.isRegularFile(p))
                return p;
        }
        p = root.resolve("lib").resolve(filename);
        if (Files.isRegularFile(p))
            return p;
        if (group != null && !group.isEmpty()) {
            p = root.resolve(group).resolve(filename);
            if (Files.isRegularFile(p))
                return p;
            p = root.resolve(group + '-' + filename);
            if (Files.isRegularFile(p))
                return p;
        }
        p = root.resolve(filename);
        if (Files.isRegularFile(p))
            return p;
        return null;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Paths">
    /////////// Paths ///////////////////////////////////
    /**
     * Returns the path or paths to the given file descriptor.
     * The given descriptor can be a dependency, a file name (relative to the app cache)
     * or a glob pattern (again, relative to the app cache). The returned list can contain more than one element
     * if a dependency is given and it resolves to more than a single artifact, or if a glob pattern is given,
     * which matches more than one file.
     */
    private List<Path> resolve(String p) {
        if (p == null)
            return null;

        try {
            final List<Path> res;

            final boolean isDependency = isDependency(p);
            final Path path;
            if (!isDependency && (path = Paths.get(p)).isAbsolute())
                res = singletonList(sanitize(path));
            else if (isDependency)
                res = resolveDependency(p, "jar");
            else if (isGlob(p))
                res = listDir(verifyAppCache(), p, false);
            else
                res = singletonList(sanitize(verifyAppCache().resolve(p)));

            log(LOG_DEBUG, "resolve " + p + " -> " + res);
            if (res == null || res.isEmpty())
                throw new RuntimeException("Dependency " + p + " was not found.");
            return res;
        } catch (Exception e) {
            throw new RuntimeException("Could not resolve item " + p, e);
        }
    }

    private List<Path> resolve(List<String> ps) {
        if (ps == null)
            return null;
        final List<Path> res = new ArrayList<Path>(ps.size());

        // performance enhancement
        if (true) {
            boolean hasDependencies = false;
            for (String p : ps) {
                if (isDependency(p)) {
                    hasDependencies = true;
                    break;
                }
            }
            if (hasDependencies) {
                final ArrayList<String> deps = new ArrayList<>();
                final ArrayList<String> paths = new ArrayList<>();
                for (String p : ps)
                    (isDependency(p) ? deps : paths).add(p);

                res.addAll(nullToEmpty(resolveDependencies(deps, "jar")));
                for (String p : paths)
                    res.addAll(resolve(p));
                return res;
            }
        }

        for (String p : ps)
            res.addAll(resolve(p));
        return res;
    }

    /**
     * Every path emitted by the capsule to the app's command line, system properties or environment variables is
     * first passed through this method. Caplets that relocate files should override it.
     *
     * @param p the path
     * @return the processed path
     */
    protected String processOutgoingPath(Path p) {
        return (_ct = getCallTarget(Capsule.class)) != null ? _ct.processOutgoingPath(p) : processOutgoingPath0(p);
    }

    private String processOutgoingPath0(Path p) {
        if (p == null)
            return null;
        p = toAbsolutePath(p);

        final Path currentJavaHome = Paths.get(System.getProperty(PROP_JAVA_HOME));
        if (p.startsWith(Paths.get(System.getProperty(PROP_JAVA_HOME))))
            p = move(p, currentJavaHome, getJavaHome());

        return p.toString();
    }

    private List<String> processOutgoingPath(List<Path> ps) {
        if (ps == null)
            return null;
        final List<String> res = new ArrayList<>(ps.size());
        for (Path p : ps)
            res.add(processOutgoingPath(p));
        return res;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="JAR Extraction">
    /////////// JAR Extraction ///////////////////////////////////
    private static void extractJar(JarInputStream jar, Path targetDir) throws IOException {
        for (JarEntry entry; (entry = jar.getNextJarEntry()) != null;) {
            if (entry.isDirectory() || !shouldExtractFile(entry.getName()))
                continue;

            writeFile(targetDir, entry.getName(), jar);
        }
    }

    private static boolean shouldExtractFile(String fileName) {
        if (fileName.equals(Capsule.class.getName().replace('.', '/') + ".class")
                || (fileName.startsWith(Capsule.class.getName().replace('.', '/') + "$") && fileName.endsWith(".class")))
            return false;
        if (fileName.endsWith(".class"))
            return false;
        if (fileName.startsWith("capsule/"))
            return false;
        if (fileName.startsWith("META-INF/"))
            return false;
        return true;
    }

    private Path mergeCapsule(Path wrapperCapsule, Path wrappedCapsule, Path outCapsule) throws IOException {
        try {
            if (Objects.equals(wrapperCapsule, wrappedCapsule)) {
                Files.copy(wrappedCapsule, outCapsule);
                return outCapsule;
            }

            final String wrapperVersion = VERSION;
            final String wrappedVersion;
            try {
                wrappedVersion = getCapsuleVersion(newClassLoader(null, wrapperCapsule).loadClass(Capsule.class.getName()));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(wrapperCapsule + " is not a valid capsule");
            }
            if (wrappedVersion == null)
                throw new RuntimeException(wrapperCapsule + " is not a valid capsule");
            if (Integer.parseInt(getBefore(wrapperVersion, '.')) != Integer.parseInt(getBefore(wrappedVersion, '.')))
                throw new RuntimeException("Incompatible Capsule versions: " + wrapperCapsule + " (" + wrapperVersion + "), " + wrappedCapsule + " (" + wrappedVersion + ")");
            final int higherVersion = compareVersions(wrapperVersion, wrappedVersion);

            try (final OutputStream os = Files.newOutputStream(outCapsule);
                 final JarInputStream wr = openJarInputStream(wrapperCapsule);
                 final JarInputStream wd = copyJarPrefix(Files.newInputStream(wrappedCapsule), os)) {

                final JarInputStream first = higherVersion >= 0 ? wr : wd;
                final JarInputStream second = higherVersion < 0 ? wr : wd;

                final Manifest man = new Manifest(wd.getManifest());

                final String wrMainClass = wr.getManifest().getMainAttributes().getValue(ATTR_MAIN_CLASS);
                if (!Capsule.class.getName().equals(wrMainClass)) {
                    if (first != wr)
                        throw new RuntimeException("Main class of wrapper capsule " + wrapperCapsule + " (" + wrMainClass + ") is not " + Capsule.class.getName()
                                + " and is of lower version ( " + wrapperVersion + ") than that of the wrapped capsule " + wrappedCapsule + " (" + wrappedVersion + "). Cannot merge.");
                    man.getMainAttributes().putValue(ATTR_MAIN_CLASS, wrMainClass);
                }

                final List<String> wrCaplets = nullToEmpty(parse(wr.getManifest().getMainAttributes().getValue(name(ATTR_CAPLETS))));
                final ArrayList<String> caplets = new ArrayList<>(nullToEmpty(parse(man.getMainAttributes().getValue(name(ATTR_CAPLETS)))));
                addAllIfAbsent(caplets, wrCaplets);

                man.getMainAttributes().putValue(name(ATTR_CAPLETS), join(caplets, " "));

                try (final JarOutputStream out = new JarOutputStream(os, man)) {
                    final Set<String> copied = new HashSet<>();
                    for (JarEntry entry; (entry = first.getNextJarEntry()) != null;) {
                        if (!entry.getName().equals(MANIFEST_NAME)) {
                            out.putNextEntry(new JarEntry(entry));
                            copy(first, out);
                            out.closeEntry();
                            copied.add(entry.getName());
                        }
                    }
                    for (JarEntry entry; (entry = second.getNextJarEntry()) != null;) {
                        if (!entry.getName().equals(MANIFEST_NAME) && !copied.contains(entry.getName())) {
                            out.putNextEntry(new JarEntry(entry));
                            copy(second, out);
                            out.closeEntry();
                        }
                    }

                    log(LOG_VERBOSE, "Testing capsule " + outCapsule);
                    newCapsule0(newClassLoader(ClassLoader.getSystemClassLoader(), outCapsule), outCapsule); // test capsule
                    log(LOG_VERBOSE, "Done testing capsule " + outCapsule);

                    return outCapsule;
                }
            }
        } catch (Exception e) {
            try {
                Files.delete(outCapsule);
            } catch (IOException ex) {
            }
            throw e;
        }
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Path Utils">
    /////////// Path Utils ///////////////////////////////////
    private FileSystem getFileSystem() {
        return cc.jarFile != null ? cc.jarFile.getFileSystem() : FileSystems.getDefault();
    }

    private Path path(String p, String... more) {
        return getFileSystem().getPath(p, more);
    }

    private Path path(URI uri) {
        return getFileSystem().provider().getPath(uri);
    }

    private List<Path> toPath(List<String> ps) {
        if (ps == null)
            return null;
        final List<Path> aps = new ArrayList<Path>(ps.size());
        for (String p : ps)
            aps.add(path(p));
        return aps;
    }

    private static Path toAbsolutePath(Path p) {
        return p != null ? p.toAbsolutePath().normalize() : null;
    }

    private static List<Path> resolve(Path root, List<String> ps) {
        if (ps == null)
            return null;
        final List<Path> aps = new ArrayList<Path>(ps.size());
        for (String p : ps)
            aps.add(root.resolve(p));
        return aps;
    }

    private List<Path> sanitize(List<Path> ps) {
        if (ps == null)
            return null;
        final List<Path> aps = new ArrayList<Path>(ps.size());
        for (Path p : ps)
            aps.add(sanitize(p));
        return aps;
    }

    private Path sanitize(Path p) {
        final Path path = p.toAbsolutePath().normalize();
        if (getAppCache() != null && path.startsWith(getAppCache()))
            return path;
        if (path.startsWith(getJavaHome()) || path.startsWith(Paths.get(System.getProperty(PROP_JAVA_HOME))))
            return path;
        throw new IllegalArgumentException("Path " + p + " is not local to app cache " + getAppCache());
    }

    private static String expandCommandLinePath(String str) {
        if (str == null)
            return null;
//        if (isWindows())
//            return str;
//        else
        return str.startsWith("~/") ? str.replace("~", getProperty(PROP_USER_HOME)) : str;
    }

    private static Path toFriendlyPath(Path p) {
        if (p.isAbsolute()) {
            Path rel = p.getFileSystem().getPath("").toAbsolutePath().relativize(p);
            if (rel.normalize().equals(rel))
                return rel;
        }
        return p;
    }

    /**
     * Returns a path to a file or directory moved from {@code fromDir} to {@code toDir}.
     * This method does not actually moves any files in the filesystem.
     *
     * @param what    the path to move; must start with {@code fromDir}
     * @param fromDir the directory containing {@code what}
     * @param toDir   the directory {@code what} is moved to
     * @return the moved path, which will start with {@code toDir}.
     */
    protected static Path move(Path what, Path fromDir, Path toDir) {
        if (!what.startsWith(fromDir))
            throw new IllegalArgumentException(what + " is not under " + fromDir);
        return toDir.resolve(fromDir.relativize(what));
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="OS">
    /////////// OS ///////////////////////////////////
    /**
     * Tests whether the current OS is Windows.
     */
    protected static final boolean isWindows() {
        return OS.startsWith("windows");
    }

    /**
     * Tests whether the current OS is MacOS.
     */
    protected static final boolean isMac() {
        return OS.startsWith("mac");
    }

    /**
     * Tests whether the current OS is UNIX/Linux.
     */
    protected static final boolean isUnix() {
        return OS.contains("nux") || OS.contains("solaris") || OS.contains("aix");
    }

    private static String getOS() {
        if (isWindows())
            return OS_WINDOWS;
        if (isMac())
            return OS_MACOS;
        if (OS.contains("solaris"))
            return OS_SOLARIS;
        if (isUnix())
            return OS_LINUX;
        else
            throw new RuntimeException("Unrecognized OS: " + System.getProperty(PROP_OS_NAME));
    }

    /**
     * The suffix of a native library on this OS.
     */
    protected static final String getNativeLibExtension() {
        if (isWindows())
            return "dll";
        if (isMac())
            return "dylib";
        if (isUnix())
            return "so";
        throw new RuntimeException("Unsupported operating system: " + System.getProperty(PROP_OS_NAME));
    }

    private static long getMaxCommandLineLength() {
        if (isWindows())
            return WINDOWS_MAX_CMD;
        return Long.MAX_VALUE;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="JAR Utils">
    /////////// JAR Utils ///////////////////////////////////
    private static JarInputStream openJarInputStream(Path jar) throws IOException {
        return new JarInputStream(skipToZipStart(Files.newInputStream(jar), null));
    }

    private static JarInputStream copyJarPrefix(InputStream is, OutputStream os) throws IOException {
        return new JarInputStream(skipToZipStart(is, null));
    }

    protected static InputStream getEntryInputStream(Path jar, String name) throws IOException {
        return getEntry(openJarInputStream(jar), name);
    }

    private static InputStream getEntry(ZipInputStream zis, String name) throws IOException {
        for (ZipEntry entry; (entry = zis.getNextEntry()) != null;) {
            if (entry.getName().equals(name))
                return zis;
        }
        return null;
    }

    private static String getMainClass(Path jar) {
        return getMainClass(getManifest(jar));
    }

    private static String getMainClass(Manifest manifest) {
        if (manifest == null)
            return null;
        return manifest.getMainAttributes().getValue(ATTR_MAIN_CLASS);
    }

    private static Manifest getManifest(Path jar) {
        try (JarInputStream jis = openJarInputStream(jar)) {
            return jis.getManifest();
        } catch (IOException e) {
            throw new RuntimeException("Error reading manifest from " + jar, e);
        }
    }

    private static final int[] ZIP_HEADER = new int[]{'P', 'K', 0x03, 0x04};

    private static InputStream skipToZipStart(InputStream is, OutputStream os) throws IOException {
        if (!is.markSupported())
            is = new BufferedInputStream(is);
        int state = 0;
        for (;;) {
            if (state == 0)
                is.mark(ZIP_HEADER.length);
            final int b = is.read();
            if (b < 0)
                throw new IllegalArgumentException("Not a JAR/ZIP file");
            if (state >= 0 && b == ZIP_HEADER[state]) {
                state++;
                if (state == ZIP_HEADER.length)
                    break;
            } else {
                state = -1;
                if (b == '\n' || b == 0) // start matching on \n and \0
                    state = 0;
            }
            if (os != null)
                os.write(b);
        }
        is.reset();
        return is;
    }

    // visible for testing
    static Path createPathingJar(Path dir, List<Path> cp) {
        try {
            dir = dir.toAbsolutePath();
            final List<String> paths = createPathingClassPath(dir, cp);

            final Path pathingJar = Files.createTempFile(dir, "capsule_pathing_jar", ".jar");
            final Manifest man = new Manifest();
            man.getMainAttributes().putValue(ATTR_MANIFEST_VERSION, "1.0");
            man.getMainAttributes().putValue(ATTR_CLASS_PATH, join(paths, " "));
            new JarOutputStream(Files.newOutputStream(pathingJar), man).close();

            return pathingJar;
        } catch (IOException e) {
            throw new RuntimeException("Pathing JAR creation failed", e);
        }
    }

    private static List<String> createPathingClassPath(Path dir, List<Path> cp) {
        boolean allPathsHaveSameRoot = true;
        for (Path p : cp) {
            if (!dir.getRoot().equals(p.getRoot()))
                allPathsHaveSameRoot = false;
        }

        final List<String> paths = new ArrayList<>(cp.size());
        for (Path p : cp) { // In order to use the Class-Path attribute, we must either relativize the paths, or specifiy them as file URLs
            if (allPathsHaveSameRoot)
                paths.add(dir.relativize(p).toString());
            else
                paths.add(p.toUri().toString());
        }
        return paths;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="File Utils">
    /////////// File Utils ///////////////////////////////////
    private static void writeFile(Path targetDir, String fileName, InputStream is) throws IOException {
        fileName = toNativePath(fileName);
        final String dir = getDirectory(fileName);
        if (dir != null)
            Files.createDirectories(targetDir.resolve(dir));

        final Path targetFile = targetDir.resolve(fileName);
        Files.copy(is, targetFile);
    }

    private static String toNativePath(String filename) {
        final char ps = (!filename.contains("/") && filename.contains("\\")) ? '\\' : '/';
        return ps != FILE_SEPARATOR_CHAR ? filename.replace(ps, FILE_SEPARATOR_CHAR) : filename;
    }

    private static String getDirectory(String filename) {
        final int index = filename.lastIndexOf(FILE_SEPARATOR_CHAR);
        if (index < 0)
            return null;
        return filename.substring(0, index);
    }

    /**
     * Deletes the given file or directory (even if nonempty).
     */
    protected static void delete(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
                for (Path f : ds)
                    delete(f);
            }
        }
        Files.delete(path);
    }

    /**
     * Copies the source file or directory (recursively) to the target location.
     */
    protected static void copy(Path source, Path target) throws IOException {
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
        if (Files.isDirectory(source)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(source)) {
                for (Path f : ds)
                    copy(f, target.resolve(f.getFileName()));
            }
        }
    }

    private static void ensureExecutable(Path file) {
        if (!Files.isExecutable(file)) {
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
                if (!perms.contains(PosixFilePermission.OWNER_EXECUTE)) {
                    Set<PosixFilePermission> newPerms = EnumSet.copyOf(perms);
                    newPerms.add(PosixFilePermission.OWNER_EXECUTE);
                    Files.setPosixFilePermissions(file, newPerms);
                }
            } catch (UnsupportedOperationException e) {
            } catch (IOException e) {
                throw rethrow(e);
            }
        }
    }

    /**
     * Copies the input stream to the output stream.
     * Neither stream is closed when the method returns.
     */
    protected static void copy(InputStream is, OutputStream out) throws IOException {
        final byte[] buffer = new byte[1024];
        for (int bytesRead; (bytesRead = is.read(buffer)) != -1;)
            out.write(buffer, 0, bytesRead);
        out.flush();
    }

    private static Path getTempDir() {
        try {
            return Paths.get(getProperty(PROP_TMP_DIR));
        } catch (Exception e) {
            return null;
        }
    }

    private static Path getExistingAncestor(Path p) {
        p = p.toAbsolutePath().getParent();
        while (p != null && !Files.exists(p))
            p = p.getParent();
        return p;
    }

    /**
     * Returns the permissions of the given file or directory.
     */
    protected static FileAttribute<?>[] getPermissions(Path p) throws IOException {
        final List<FileAttribute> attrs = new ArrayList<>();

        final PosixFileAttributeView posix = Files.getFileAttributeView(p, PosixFileAttributeView.class);
        if (posix != null)
            attrs.add(PosixFilePermissions.asFileAttribute(posix.readAttributes().permissions()));

        return attrs.toArray(new FileAttribute[attrs.size()]);
    }

    /**
     * Returns the contents of a directory. <br>
     * Passing {@code null} as the glob pattern is the same as passing {@code "*"}
     *
     * @param dir     the directory
     * @param glob    the glob pattern to use to filter the entries, or {@code null} if all entries are to be returned
     * @param regular whether only regular files should be returned
     */
    protected static final List<Path> listDir(Path dir, String glob, boolean regular) {
        return listDir(dir, glob, false, regular, new ArrayList<Path>());
    }

    private static List<Path> listDir(Path dir, String glob, boolean recursive, boolean regularFile, List<Path> res) {
        return listDir(dir, splitGlob(glob), recursive, regularFile, res);
    }

    @SuppressWarnings("null")
    private static List<Path> listDir(Path dir, List<String> globs, boolean recursive, boolean regularFile, List<Path> res) {
        PathMatcher matcher = null;
        if (globs != null) {
            while (!globs.isEmpty() && "**".equals(globs.get(0))) {
                recursive = true;
                globs = globs.subList(1, globs.size());
            }
            if (!globs.isEmpty())
                matcher = dir.getFileSystem().getPathMatcher("glob:" + globs.get(0));
        }

        final List<Path> ms = (matcher != null || recursive) ? new ArrayList<Path>() : res;
        final List<Path> mds = matcher != null ? new ArrayList<Path>() : null;
        final List<Path> rds = recursive ? new ArrayList<Path>() : null;

        try (DirectoryStream<Path> fs = Files.newDirectoryStream(dir)) {
            for (Path f : fs) {
                if (recursive && Files.isDirectory(f))
                    rds.add(f);
                if (matcher == null) {
                    if (!regularFile || Files.isRegularFile(f))
                        ms.add(f);
                } else {
                    if (matcher.matches(f.getFileName())) {
                        if (globs.size() == 1 && (!regularFile || Files.isRegularFile(f)))
                            ms.add(f);
                        else if (Files.isDirectory(f))
                            mds.add(f);
                    }
                }
            }
        } catch (IOException e) {
            throw rethrow(e);
        }

        sort(ms); // sort to give same reults on all platforms (hopefully)
        if (res != ms) {
            res.addAll(ms);

            recurse:
            for (List<Path> ds : asList(mds, rds)) {
                if (ds == null)
                    continue;
                sort(ds);
                final List<String> gls = (ds == mds ? globs.subList(1, globs.size()) : globs);
                for (Path d : ds)
                    listDir(d, gls, recursive, regularFile, res);
            }
        }

        return res;
    }

    private static boolean isGlob(String s) {
        return s.contains("*") || s.contains("?") || s.contains("{") || s.contains("[");
    }

    private static List<String> splitGlob(String glob) { // splits glob pattern by directory
        return glob != null ? asList(glob.split(FILE_SEPARATOR_CHAR == '\\' ? "\\\\" : FILE_SEPARATOR)) : null;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="JRE Installations">
    /////////// JRE Installations ///////////////////////////////////
    private static boolean isJDK(Path javaHome) {
        final String name = javaHome.toString().toLowerCase();
        return name.contains("jdk") && !name.contains("jre");
    }

    /**
     * Returns all found Java installations.
     *
     * @return a map from installations' versions to their respective (possibly multiple) paths
     */
    protected static Map<String, List<Path>> getJavaHomes() {
        if (JAVA_HOMES == null) {
            try {
                Path homesDir = null;
                for (Path d = Paths.get(getProperty(PROP_JAVA_HOME)); d != null; d = d.getParent()) {
                    if (isJavaDir(d.getFileName().toString()) != null) {
                        homesDir = d.getParent();
                        break;
                    }
                }
                Map<String, List<Path>> homes = getJavaHomes(homesDir);
                if (homes != null && isWindows())
                    homes = windowsJavaHomesHeuristics(homesDir, homes);
                JAVA_HOMES = homes;
            } catch (IOException e) {
                throw rethrow(e);
            }
        }
        return JAVA_HOMES;
    }

    private static Map<String, List<Path>> windowsJavaHomesHeuristics(Path dir, Map<String, List<Path>> homes) throws IOException {
        Path dir2 = null;
        if (dir.startsWith(WINDOWS_PROGRAM_FILES_1))
            dir2 = WINDOWS_PROGRAM_FILES_2.resolve(WINDOWS_PROGRAM_FILES_1.relativize(dir));
        else if (dir.startsWith(WINDOWS_PROGRAM_FILES_2))
            dir2 = WINDOWS_PROGRAM_FILES_1.resolve(WINDOWS_PROGRAM_FILES_2.relativize(dir));
        if (dir2 != null) {
            Map<String, List<Path>> allHomes = new HashMap<>(nullToEmpty(homes));
            multiputAll(allHomes, nullToEmpty(getJavaHomes(dir2)));
            return allHomes;
        } else
            return homes;
    }

    private static Map<String, List<Path>> getJavaHomes(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir))
            return null;
        final Map<String, List<Path>> dirs = new HashMap<String, List<Path>>();
        try (DirectoryStream<Path> fs = Files.newDirectoryStream(dir)) {
            for (Path f : fs) {
                String ver;
                List<Path> homes;
                if (Files.isDirectory(f) && (ver = isJavaDir(f.getFileName().toString())) != null
                        && (homes = searchJavaHomeInDir(f)) != null) {
                    if (parseJavaVersion(ver)[3] == 0)
                        ver = getActualJavaVersion(homes.get(0));
                    multiput(dirs, ver, homes);
                }
            }
        }
        return dirs;
    }

    private static String getJavaVersion(Path home) {
        if (home == null)
            return null;
        String ver;
        for (Path f = home; f != null && f.getNameCount() > 0; f = f.getParent()) {
            ver = isJavaDir(f.getFileName().toString());
            if (ver != null)
                return ver;
        }
        return getActualJavaVersion(home);
    }

    // visible for testing
    static String isJavaDir(String fileName) {
        /*
         * This method considers some well-known Java home directory naming schemes.
         * It will likely require changes to accomodate other schemes used by various package managers.
         */
        fileName = fileName.toLowerCase();
        if (fileName.startsWith("jdk") || fileName.startsWith("jre") || fileName.endsWith(".jdk") || fileName.endsWith(".jre")) {
            if (fileName.startsWith("jdk") || fileName.startsWith("jre"))
                fileName = fileName.substring(3);
            if (fileName.endsWith(".jdk") || fileName.endsWith(".jre"))
                fileName = fileName.substring(0, fileName.length() - 4);
            return shortJavaVersion(fileName);
        } else if (fileName.startsWith("java-") && (fileName.contains("-openjdk") || fileName.contains("-oracle"))) {
            final Matcher m = Pattern.compile("java-([0-9]+)-").matcher(fileName);
            m.find();
            return shortJavaVersion(m.group(1));
        } else
            return null;
    }

    private static List<Path> searchJavaHomeInDir(Path dir) throws IOException {
        final List<Path> homes = new ArrayList<>();
        final boolean jdk = isJDK(dir);
        try (DirectoryStream<Path> fs = Files.newDirectoryStream(dir)) {
            for (Path f : fs) {
                if (Files.isDirectory(f)) {
                    if (isJavaHome(f))
                        homes.add(f.toAbsolutePath());
                    if (homes.size() >= 2 || (homes.size() >= 1 && !(jdk || isJDK(f))))
                        break;
                    final List<Path> rec = searchJavaHomeInDir(f);
                    if (rec != null)
                        homes.addAll(rec);
                }
            }
        }
        return homes;
    }

    private static boolean isJavaHome(Path dir) {
        return Files.isRegularFile(dir.resolve("bin").resolve("java" + (isWindows() ? ".exe" : "")));
    }

    private static Path getJavaExecutable0(Path javaHome) {
        final String exec = (isWindows() && System.console() == null) ? "javaw" : "java";
        return javaHome.resolve("bin").resolve(exec + (isWindows() ? ".exe" : ""));
    }

    private static final Pattern PAT_JAVA_VERSION_LINE = Pattern.compile(".*?\"(.+?)\"");

    private static String getActualJavaVersion(Path javaHome) {
        try {
            final String versionLine = exec(1, getJavaExecutable0(javaHome).toString(), "-version").get(0);
            final Matcher m = PAT_JAVA_VERSION_LINE.matcher(versionLine);
            if (!m.matches())
                throw new IllegalArgumentException("Could not parse version line: " + versionLine);
            final String version = m.group(1);

            return version;
        } catch (Exception e) {
            throw rethrow(e);
        }
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Version Strings">
    /////////// Version Strings ///////////////////////////////////
    // visible for testing
    static String shortJavaVersion(String v) {
        try {
            final String[] vs = v.split(SEPARATOR_DOT);
            if (vs.length == 1) {
                if (Integer.parseInt(vs[0]) < 5)
                    throw new RuntimeException("Unrecognized major Java version: " + v);
                v = "1." + v + ".0";
            }
            if (vs.length == 2)
                v += ".0";
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String majorJavaVersion(String v) {
        if (v == null)
            return null;
        final String[] vs = v.split(SEPARATOR_DOT);
        if (vs.length == 1)
            return vs[0];
        if (vs.length >= 2)
            return vs[1];
        throw new AssertionError("unreachable");
    }

    /**
     * Compares two dotted software versions, regarding only the first several version components.
     *
     * @param a first version
     * @param b second version
     * @param n the number of (most significant) components to consider
     * @return {@code 0} if {@code a == b}; {@code > 0} if {@code a > b}; {@code < 0} if {@code a < b};
     */
    protected static final int compareVersions(String a, String b, int n) {
        return compareVersions(parseJavaVersion(a), parseJavaVersion(b), n);
    }

    /**
     * Compares two dotted software versions.
     *
     * @param a first version
     * @param b second version
     * @return {@code 0} if {@code a == b}; {@code > 0} if {@code a > b}; {@code < 0} if {@code a < b};
     */
    protected static final int compareVersions(String a, String b) {
        return compareVersions(parseJavaVersion(a), parseJavaVersion(b));
    }

    private static int compareVersions(int[] a, int[] b) {
        return compareVersions(a, b, 5);
    }

    private static int compareVersions(int[] a, int[] b, int n) {
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i])
                return a[i] - b[i];
        }
        return 0;
    }

    private static boolean equals(int[] a, int[] b, int n) {
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i])
                return false;
        }
        return true;
    }

    private static final Pattern PAT_JAVA_VERSION = Pattern.compile("(?<major>\\d+)\\.(?<minor>\\d+)(?:\\.(?<patch>\\d+))?(_(?<update>\\d+))?(-(?<pre>[^-]+))?(-(?<build>.+))?");

    // visible for testing
    static int[] parseJavaVersion(String v) {
        final Matcher m = PAT_JAVA_VERSION.matcher(v);
        if (!m.matches())
            throw new IllegalArgumentException("Could not parse version: " + v);
        final int[] ver = new int[5];
        ver[0] = toInt(m.group("major"));
        ver[1] = toInt(m.group("minor"));
        ver[2] = toInt(m.group("patch"));
        ver[3] = toInt(m.group("update"));
        final String pre = m.group("pre");
        if (pre != null) {
            if (pre.startsWith("rc"))
                ver[4] = -1;
            else if (pre.startsWith("beta"))
                ver[4] = -2;
            else if (pre.startsWith("ea"))
                ver[4] = -3;
        }
        return ver;
    }

    // visible for testing
    static String toJavaVersionString(int[] version) {
        final StringBuilder sb = new StringBuilder();
        sb.append(version[0]).append('.');
        sb.append(version[1]).append('.');
        sb.append(version[2]);
        if (version.length > 3 && version[3] > 0)
            sb.append('_').append(version[3]);
        if (version.length > 4 && version[4] != 0) {
            final String pre;
            switch (version[4]) {
                case -1:
                    pre = "rc";
                    break;
                case -2:
                    pre = "beta";
                    break;
                case -3:
                    pre = "ea";
                    break;
                default:
                    pre = "?";
            }
            sb.append('-').append(pre);
        }
        return sb.toString();
    }

    private static int toInt(String s) {
        return s != null ? Integer.parseInt(s) : 0;
    }

    private static int[] toInt(String[] ss) {
        int[] res = new int[ss.length];
        for (int i = 0; i < ss.length; i++)
            res[i] = ss[i] != null ? Integer.parseInt(ss[i]) : 0;
        return res;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="String Expansion">
    /////////// String Expansion ///////////////////////////////////
    private static final Pattern PAT_VAR = Pattern.compile("\\$(?:([a-zA-Z0-9_\\-]+)|(?:\\{([^\\}]*)\\}))");

    private String expand(String str) {
        if (str == null)
            return null;

        final StringBuffer sb = new StringBuffer();
        final Matcher m = PAT_VAR.matcher(str);
        while (m.find())
            m.appendReplacement(sb, Matcher.quoteReplacement(getVarValue(xor(m.group(1), m.group(2)))));
        m.appendTail(sb);
        str = sb.toString();

        // str = expandCommandLinePath(str);
        str = str.replace('/', FILE_SEPARATOR_CHAR);
        return str;
    }

    /**
     * Resolves {@code $VARNAME} or {@code ${VARNAME}} in attribute values.
     *
     * @param var the variable name
     * @return the variable's value
     */
    protected String getVarValue(String var) {
        return (_ct = getCallTarget(Capsule.class)) != null ? _ct.getVarValue(var) : getVarValue0(var);
    }

    private String getVarValue0(String var) {
        String value = null;
        switch (var) {
            case VAR_CAPSULE_DIR:
                if (getAppCache() == null)
                    throw new IllegalStateException("Cannot resolve variable $" + var + "; capsule not expanded");
                value = processOutgoingPath(getAppCache());
                break;

            case VAR_CAPSULE_APP:
                if (getAppId() == null)
                    throw new RuntimeException("Cannot resolve variable $" + var + " in an empty capsule.");
                value = getAppId();
                break;

            case VAR_CAPSULE_JAR:
            case "0":
                value = processOutgoingPath(getJarFile());
                break;

            case VAR_JAVA_HOME:
                final String jhome = processOutgoingPath(getJavaHome());
                if (jhome == null)
                    throw new RuntimeException("Cannot resolve variable $" + var + "; Java home not set.");
                value = jhome;
                break;
        }
        if (value == null) {
            value = getProperty(var);
            if (value != null)
                log(LOG_DEBUG, "Resolved variable $" + var + " with a property");
        }
        if (value == null) {
            value = getenv(var);
            if (value != null)
                log(LOG_DEBUG, "Resolved variable $" + var + " with an environement variable");
        }
        if (value == null)
            throw new RuntimeException("Cannot resolve variable $" + var);
        return value;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="String Utils">
    /////////// String Utils ///////////////////////////////////
    private static String toString(Object obj) {
        return obj != null ? obj.toString() : null;
    }

    private static List<String> split(String str, String separator) {
        if (str == null)
            return null;
        final String[] es = str.split(separator);
        final List<String> list = new ArrayList<>(es.length);
        for (String e : es) {
            e = e.trim();
            if (!e.isEmpty())
                list.add(e);
        }
        return list;
    }

    private static Map<String, String> split(String map, char kvSeparator, String separator, String defaultValue) {
        if (map == null)
            return null;
        Map<String, String> m = new LinkedHashMap<>();
        for (String entry : Capsule.split(map, separator)) {
            final String key = getBefore(entry, kvSeparator);
            String value = getAfter(entry, kvSeparator);
            if (value == null) {
                if (defaultValue != null)
                    value = defaultValue;
                else
                    throw new IllegalArgumentException("Element " + entry + " in \"" + map + "\" is not a key-value entry separated with " + kvSeparator + " and no default value provided");
            }
            m.put(key.trim(), value.trim());
        }
        return m;
    }

    private static String join(Collection<?> coll, String separator) {
        if (coll == null)
            return null;
        if (coll.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder();
        for (Object e : coll) {
            if (e != null)
                sb.append(e).append(separator);
        }
        sb.delete(sb.length() - separator.length(), sb.length());
        return sb.toString();
    }

    private static String getBefore(String s, char separator) {
        final int i = s.indexOf(separator);
        if (i < 0)
            return s;
        return s.substring(0, i);
    }

    private static String getAfter(String s, char separator) {
        final int i = s.indexOf(separator);
        if (i < 0)
            return null;
        return s.substring(i + 1);
    }

    private static long getStringsLength(Collection<?> coll) {
        if (coll == null)
            return 0;
        long len = 0;
        for (Object o : coll)
            len += o.toString().length();
        return len;
    }

    private static String emptyToNull(String s) {
        if (s == null)
            return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static <T> T xor(T x, T y) {
        assert x == null ^ y == null;
        return x != null ? x : y;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Collection Utils">
    /////////// Collection Utils ///////////////////////////////////
    @SuppressWarnings("unchecked")
    private static <T> List<T> nullToEmpty(List<T> list) {
        return list != null ? list : (List<T>) emptyList();
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> nullToEmpty(Map<K, V> map) {
        return map != null ? map : (Map<K, V>) emptyMap();
    }

    private static <T> List<T> emptyToNull(List<T> list) {
        return (list != null && !list.isEmpty()) ? list : null;
    }

    private static <K, V> Map<K, V> emptyToNull(Map<K, V> map) {
        return (map != null && !map.isEmpty()) ? map : null;
    }

    private static <K, V> Map<K, List<V>> multiput(Map<K, List<V>> map, K key, V value) {
        List<V> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>();
            map.put(key, list);
        }
        list.add(value);
        return map;
    }

    private static <K, V> Map<K, List<V>> multiput(Map<K, List<V>> map, K key, List<V> values) {
        if (values == null)
            return map;
        List<V> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>();
            map.put(key, list);
        }
        list.addAll(values);
        return map;
    }

    private static <K, V> Map<K, List<V>> multiputAll(Map<K, List<V>> map, Map<K, List<V>> map2) {
        for (Map.Entry<K, List<V>> entry : map2.entrySet()) {
            List<V> list = map.get(entry.getKey());
            if (list == null) {
                list = new ArrayList<>();
                map.put(entry.getKey(), list);
            }
            list.addAll(entry.getValue());
        }
        return map;
    }

    private static <T> T first(List<T> c) {
        if (c == null || c.isEmpty())
            throw new IllegalArgumentException("Not found");
        return c.get(0);
    }

    private static <T> T firstOrNull(List<T> c) {
        if (c == null || c.isEmpty())
            return null;
        return c.get(0);
    }

    private static <C extends Collection<T>, T> C addAll(C c, Collection<T> c1) {
        if (c1 != null)
            c.addAll(c1);
        return c;
    }

    private static <C extends Collection<T>, T> C addAllIfAbsent(C c, Collection<T> c1) {
        for (T e : c1) {
            if (!c.contains(e))
                c.add(e);
        }
        return c;
    }

    private static <M extends Map<K, V>, K, V> M putAllIfAbsent(M m, Map<K, V> m1) {
        for (Map.Entry<K, V> entry : m1.entrySet()) {
            if (!m.containsKey(entry.getKey()))
                m.put(entry.getKey(), entry.getValue());
        }
        return m;
    }

    @SafeVarargs
    private static <T> Set<T> immutableSet(T... elems) {
        return unmodifiableSet(new HashSet<T>(asList(elems)));
    }

    private static boolean isEmpty(Object x) {
        if (x == null)
            return true;
        if (x instanceof String)
            return ((String) x).isEmpty();
        if (x instanceof Collection)
            return ((Collection) x).isEmpty();
        if (x instanceof Map)
            return ((Map) x).isEmpty();
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> T merge(T v1, T v2) {
        if (v2 == null)
            return v1;
        if (v1 instanceof Collection) {
            final Collection<Object> c1 = (Collection<Object>) v1;
            final Collection<Object> c2 = (Collection<Object>) v2;
            final Collection<Object> cm;
            if (v1 instanceof List)
                cm = new ArrayList<>(c1.size() + c2.size());
            else if (v1 instanceof Set)
                cm = new HashSet<>(c1.size() + c2.size());
            else
                throw new RuntimeException("Unhandled type: " + v1.getClass().getName());
            cm.addAll(c1);
            addAllIfAbsent(cm, c2);
            return (T) cm;
        } else if (v1 instanceof Map) {
            final Map<Object, Object> mm = new HashMap<>();
            mm.putAll((Map<Object, Object>) v1);
            mm.putAll((Map<Object, Object>) v2);
            return (T) mm;
        } else
            return v2;
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Reflection Utils">
    /////////// Reflection Utils ///////////////////////////////////
    private static Method getMethod(Capsule capsule, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        for (Capsule c = capsule.cc; c != null; c = c.sup) {
            try {
                return getMethod(c.getClass(), name, parameterTypes);
            } catch (NoSuchMethodException e) {
            }
        }
        throw new NoSuchMethodException(name + "(" + Arrays.toString(parameterTypes) + ")");
    }

    private static Method getMethod(Class<?> clazz, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        try {
            return accessible(clazz.getDeclaredMethod(name, parameterTypes));
        } catch (NoSuchMethodException e) {
            if (clazz.getSuperclass() == null)
                throw new NoSuchMethodException(name + "(" + Arrays.toString(parameterTypes) + ")");
            return getMethod(clazz.getSuperclass(), name, parameterTypes);
        }
    }

    private static <T extends AccessibleObject> T accessible(T obj) {
        if (obj == null)
            return null;
        obj.setAccessible(true);
        return obj;
    }

    private static ClassLoader newClassLoader0(ClassLoader parent, List<Path> ps) {
        try {
            final List<URL> urls = new ArrayList<>(ps.size());
            for (Path p : ps)
                urls.add(p.toUri().toURL());
            return new URLClassLoader(urls.toArray(new URL[urls.size()]), parent);
        } catch (MalformedURLException e) {
            throw new AssertionError(e);
        }
    }

    private static ClassLoader newClassLoader0(ClassLoader parent, Path... ps) {
        return newClassLoader0(parent, asList(ps));
    }

    /**
     * @deprecated marked deprecated to exclude from javadoc. Visible for testing
     */
    ClassLoader newClassLoader(ClassLoader parent, List<Path> ps) {
        return newClassLoader0(parent, ps);
    }

    private ClassLoader newClassLoader(ClassLoader parent, Path... ps) {
        return newClassLoader(parent, asList(ps));
    }

    private static boolean isStream(String className) {
        return className.startsWith("java.util.stream") || className.contains("$$Lambda") || className.contains("Spliterator");
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Misc Utils">
    /////////// Misc Utils ///////////////////////////////////
    private static String propertyOrEnv(String propName, String envVar) {
        String val = getProperty(propName);
        if (val == null)
            val = emptyToNull(getenv(envVar));
        return val;
    }

    /**
     * Returns a system property - should be used instead of {@link System#getProperty(java.lang.String) System.getProperty(propName)}.
     */
    protected static final String getProperty(String propName) {
        final String val = getProperty0(propName);
        setContext("system property", propName, val);
        return val;
    }

    private static String getProperty0(String propName) {
        return propName != null ? PROPERTIES.getProperty(propName) : null;
    }

    /**
     * Sets a system property.
     */
    protected static final void setProperty(String propName, String value) {
        PROPERTIES.setProperty(propName, value);
    }

    /**
     * Returns the value of an environment variable - should be used instead of {@link System#getenv(java.lang.String) System.getenv(envName)}.
     */
    protected static String getenv(String envName) {
        final String val = envName != null ? System.getenv(envName) : null;
        setContext("environment variable", envName, val);
        return val;
    }

    private static boolean systemPropertyEmptyOrTrue(String property) {
        final String value = getProperty(property);
        if (value == null)
            return false;
        return value.isEmpty() || Boolean.parseBoolean(value);
    }

    private static boolean systemPropertyEmptyOrNotFalse(String property) {
        final String value = getProperty(property);
        if (value == null)
            return false;
        return value.isEmpty() || !"false".equalsIgnoreCase(value);
    }

    private static boolean isThrownByCapsule(Exception e) {
        return e.getStackTrace() != null && e.getStackTrace().length > 0 && e.getStackTrace()[0].getClassName().equals(Capsule.class.getName());
    }

    private static Throwable deshadow(Throwable t) {
        return deshadow("capsule", t);
    }

    private static Throwable deshadow(String prefix, Throwable t) {
        prefix = prefix.endsWith(".") ? prefix : prefix + ".";
        final StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length; i++) {
            String className = st[i].getClassName();
            className = (className != null && className.startsWith(prefix) && className.lastIndexOf('.') > prefix.length()) ? className.substring(prefix.length()) : className;
            st[i] = new StackTraceElement(className, st[i].getMethodName(), st[i].getFileName(), st[i].getLineNumber());
        }
        t.setStackTrace(st);

        if (t.getCause() != null)
            deshadow(prefix, t.getCause());
        return t;
    }

    private static RuntimeException rethrow(Throwable t) {
        while (t instanceof InvocationTargetException)
            t = ((InvocationTargetException) t).getTargetException();
        if (t instanceof RuntimeException)
            throw (RuntimeException) t;
        if (t instanceof Error)
            throw (Error) t;
        throw new RuntimeException(t);
    }

    /**
     * Executes a command and returns its output as a list of lines.
     * The method will wait for the child process to terminate, and throw an exception if the command returns an exit value {@code != 0}.
     * <br>Same as calling {@code exec(-1, cmd}}.
     *
     * @param cmd the command
     * @return the lines output by the command
     */
    protected static List<String> exec(String... cmd) throws IOException {
        return exec(-1, cmd);
    }

    /**
     * Executes a command and returns its output as a list of lines.
     * If the number of lines read is less than {@code numLines}, or if {@code numLines < 0}, then the method will wait for the child process
     * to terminate, and throw an exception if the command returns an exit value {@code != 0}.
     *
     * @param numLines the maximum number of lines to read, or {@code -1} for an unbounded number
     * @param cmd      the command
     * @return the lines output by the command
     */
    protected static List<String> exec(int numLines, String... cmd) throws IOException {
        return exec(numLines, new ProcessBuilder(asList(cmd)));
    }

    /**
     * Executes a command and returns its output as a list of lines.
     * The method will wait for the child process to terminate, and throw an exception if the command returns an exit value {@code != 0}.
     * <br>Same as calling {@code exec(-1, pb}}.
     *
     * @param pb the {@link ProcessBuilder} that will be used to launch the command
     * @return the lines output by the command
     */
    protected static List<String> exec(ProcessBuilder pb) throws IOException {
        return exec(-1, pb);
    }

    /**
     * Executes a command and returns its output as a list of lines.
     * If the number of lines read is less than {@code numLines}, or if {@code numLines < 0}, then the method will wait for the child process
     * to terminate, and throw an exception if the command returns an exit value {@code != 0}.
     *
     * @param numLines the maximum number of lines to read, or {@code -1} for an unbounded number
     * @param pb       the {@link ProcessBuilder} that will be used to launch the command
     * @return the lines output by the command
     */
    protected static List<String> exec(int numLines, ProcessBuilder pb) throws IOException {
        final List<String> lines = new ArrayList<>();
        final Process p = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream(), Charset.defaultCharset()))) {
            for (int i = 0; numLines < 0 || i < numLines; i++) {
                final String line = reader.readLine();
                if (line == null)
                    break;
                lines.add(line);
            }
        }
        try {
            if (numLines < 0 || lines.size() < numLines) {
                final int exitValue = p.waitFor();
                if (exitValue != 0)
                    throw new RuntimeException("Command '" + join(pb.command(), " ") + "' has returned " + exitValue);
            }
            return lines;
        } catch (InterruptedException e) {
            throw rethrow(e);
        }
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Logging">
    /////////// Logging ///////////////////////////////////
    private static void setLogLevel(int level) {
        LOG_LEVEL.set(level);
    }

    /**
     * Capsule's log level
     */
    protected static final int getLogLevel() {
        final Integer level = LOG_LEVEL.get();
        return level != null ? level : LOG_NONE;
    }

    /**
     * Chooses and returns the capsules log level.
     */
    protected int chooseLogLevel() {
        return (_ct = getCallTarget(Capsule.class)) != null ? _ct.chooseLogLevel() : chooseLogLevel0();
    }

    private int chooseLogLevel0() {
        String level = getProperty(PROP_LOG_LEVEL);
        if (level == null && oc.manifest != null)
            level = getAttribute(ATTR_LOG_LEVEL);
        return getLogLevel(level);
    }

    private static int getLogLevel(String level) {
        if (level == null || level.isEmpty())
            level = "QUIET";
        switch (level.toUpperCase()) {
            case "NONE":
                return LOG_NONE;
            case "QUIET":
                return LOG_QUIET;
            case "VERBOSE":
                return LOG_VERBOSE;
            case "DEBUG":
            case "ALL":
                return LOG_DEBUG;
            default:
                throw new IllegalArgumentException("Unrecognized log level: " + level);
        }
    }

    /**
     * Tests if the given log level is currently being logged.
     */
    protected static final boolean isLogging(int level) {
        return level <= getLogLevel();
    }

    /**
     * Prints a message to stderr if the given log-level is being logged.
     */
    protected static final void log(int level, String str) {
        if (isLogging(level))
            STDERR.println(LOG_PREFIX + str);
    }

    private static void println(String str) {
        log(LOG_QUIET, str);
    }

    private static boolean hasContext() {
        return contextType_ != null;
    }

    private static void clearContext() {
        setContext(null, null, null);
    }

    private static void setContext(String type, String key, Object value) {
//        STDERR.println("setContext: " + type + " " + key + " " + value);
//        Thread.dumpStack();

        contextType_.set(type);
        contextKey_.set(key);
        contextValue_.set(value != null ? value.toString() : null);
    }

    private static String getContext() {
        return contextType_.get() + " " + contextKey_.get() + ": " + contextValue_.get();
    }

    private static long clock() {
        return isLogging(PROFILE) ? System.nanoTime() : 0;
    }

    private static void time(String op, long start) {
        time(op, start, isLogging(PROFILE) ? System.nanoTime() : 0);
    }

    private static void time(String op, long start, long stop) {
        if (isLogging(PROFILE))
            log(PROFILE, "PROFILE " + op + " " + ((stop - start) / 1_000_000) + "ms");
    }

    /**
     * Called when an unhandled exception is thrown, to display error information to the user before shutting down.
     */
    protected void onError(Throwable t) {
        printError(t, this);
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Pipe Streams (workaround for inheritIO bug)">
    /////////// Pipe Streams (workaround for inheritIO bug) ///////////////////////////////////
    private static boolean isInheritIoBug() {
        return isWindows() && compareVersions(System.getProperty(PROP_JAVA_VERSION), "1.8.0") < 0;
    }

    private void pipeIoStreams() {
        new Thread(this, "pipe-out").start();
        new Thread(this, "pipe-err").start();
        new Thread(this, "pipe-in").start();
    }

    private boolean pipeIoStream() {
        switch (Thread.currentThread().getName()) {
            case "pipe-out":
                pipe(child.getInputStream(), STDOUT);
                return true;
            case "pipe-err":
                pipe(child.getErrorStream(), STDERR);
                return true;
            case "pipe-in":
                pipe(System.in, child.getOutputStream());
                return true;
            default:
                return false;
        }
    }

    private void pipe(InputStream in, OutputStream out) {
        try (OutputStream out1 = out) {
            final byte[] buf = new byte[1024];
            int read;
            while (-1 != (read = in.read(buf))) {
                out.write(buf, 0, read);
                out.flush();
            }
        } catch (Throwable e) {
            if (isLogging(LOG_VERBOSE))
                e.printStackTrace(STDERR);
        }
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="POSIX">
    /////////// POSIX ///////////////////////////////////
    private static int getPid(Process p) {
        try {
            java.lang.reflect.Field pidField = p.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            return pidField.getInt(p);
        } catch (Exception e) {
            return -1;
        }
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Object Methods">
    /////////// Object Methods ///////////////////////////////////
    /**
     * Throws a {@link CloneNotSupportedException}
     *
     * @deprecated marked deprecated to exclude from javadoc
     */
    @SuppressWarnings("CloneDoesntCallSuperClone")
    @Override
    protected final Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    /**
     * @deprecated marked deprecated to exclude from javadoc
     */
    @Override
    public final int hashCode() {
        return super.hashCode();
    }

    /**
     * @deprecated marked deprecated to exclude from javadoc
     */
    @Override
    public final boolean equals(Object obj) {
        return super.equals(obj);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append(getClass().getName());
        if (isLogging(LOG_DEBUG))
            sb.append('@').append(Integer.toHexString(System.identityHashCode(this)));
        if (cc != oc) {
            sb.append('(');
            for (Capsule c = cc; c != null; c = c.sup) {
                sb.append(c.getClass().getName());
                if (isLogging(LOG_DEBUG))
                    sb.append('@').append(Integer.toHexString(System.identityHashCode(c)));
                sb.append(" ");
            }
            sb.delete(sb.length() - 1, sb.length());
            sb.append(')');
        }
        sb.append('[');
        sb.append(jarFile);
        if (getAppId() != null) {
            sb.append(", ").append(getAppId());
            sb.append(", ").append(getAttribute(ATTR_APP_CLASS) != null ? getAttribute(ATTR_APP_CLASS) : getAttribute(ATTR_APP_ARTIFACT));
        } else
            sb.append(", ").append("empty");
        if (getMode() != null)
            sb.append(", ").append("mode: ").append(getMode());
        sb.append(']');
        return sb.toString();
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Capsule Loading and Launching">
    /////////// Capsule Loading and Launching ///////////////////////////////////
    /**
     * Loads the wrapped capsule when this capsule is the wrapper.
     * Caplets can override this method to provide security.
     *
     * @param parent the
     */
    protected Capsule loadTargetCapsule(ClassLoader parent, Path jarFile) {
        return (_ct = getCallTarget(Capsule.class)) != null ? _ct.loadTargetCapsule(parent, jarFile) : loadTargetCapsule0(parent, jarFile);
    }

    private Capsule loadTargetCapsule0(ClassLoader parent, Path jar) {
        return newCapsule(newClassLoader(parent, jar), jar);
    }

    // visible for testing
    static Capsule newCapsule(ClassLoader cl, Path jarFile) {
        return (Capsule) newCapsule0(cl, jarFile);
    }

    private static Object newCapsule0(ClassLoader cl, Path jarFile) {
        try {
            final ClassLoader ccl = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(cl);
                return accessible(loadCapsule(cl, jarFile).getDeclaredConstructor(Path.class)).newInstance(jarFile);
            } finally {
                Thread.currentThread().setContextClassLoader(ccl);
            }
        } catch (IncompatibleClassChangeError e) {
            throw new RuntimeException("Caplet " + jarFile + " is not compatible with this capsule (" + VERSION + ")");
        } catch (InvocationTargetException e) {
            throw rethrow(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not instantiate capsule.", e);
        }
    }

    private Capsule newCapsule(Path jarFile, Capsule pred) {
        try {
            final ClassLoader ccl = Thread.currentThread().getContextClassLoader();
            try {
                final ClassLoader cl = newClassLoader(pred.getClass().getClassLoader(), jarFile);
                Thread.currentThread().setContextClassLoader(cl);
                return accessible(loadCapsule(cl, jarFile).getDeclaredConstructor(Path.class)).newInstance(jarFile);
            } finally {
                Thread.currentThread().setContextClassLoader(ccl);
            }
        } catch (IncompatibleClassChangeError e) {
            throw new RuntimeException("Caplet " + jarFile + " is not compatible with this capsule (" + VERSION + ")");
        } catch (InvocationTargetException e) {
            throw rethrow(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not instantiate capsule.", e);
        }
    }

    private static Capsule newCapsule(String capsuleClass, Capsule pred) {
        try {
            final Class<? extends Capsule> clazz = loadCapsule(Thread.currentThread().getContextClassLoader(), capsuleClass, capsuleClass);
            assert getActualCapsuleClass(clazz) == Capsule.class;
            return accessible(clazz.getDeclaredConstructor(Capsule.class)).newInstance(pred);
        } catch (IncompatibleClassChangeError e) {
            throw new RuntimeException("Caplet " + capsuleClass + " is not compatible with this capsule (" + VERSION + ")");
        } catch (InvocationTargetException e) {
            throw rethrow(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not instantiate capsule " + capsuleClass, e);
        }
    }

    private static Class<? extends Capsule> loadCapsule(ClassLoader cl, Path jarFile) {
        final String mainClassName = getMainClass(jarFile);
        if (mainClassName != null)
            return loadCapsule(cl, mainClassName, jarFile.toString());
        throw new RuntimeException(jarFile + " does not appear to be a valid capsule.");
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Capsule> loadCapsule(ClassLoader cl, String capsuleClass, String name) {
        try {
            log(LOG_DEBUG, "Loading capsule class " + capsuleClass + " using class loader " + toString(cl));
            final Class<?> clazz = cl.loadClass(capsuleClass);
            final Class<Capsule> c = getActualCapsuleClass(clazz);
            if (c == null)
                throw new RuntimeException(name + " does not appear to be a valid capsule.");

            if (c != Capsule.class) // i.e. it's the Capsule class but in a different classloader
                accessible(c.getDeclaredField("PROPERTIES")).set(null, new Properties(PROPERTIES));

            return (Class<? extends Capsule>) clazz;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Caplet " + capsuleClass + " not found.", e);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(name + " does not appear to be a valid capsule.");
        } catch (IncompatibleClassChangeError | ClassCastException e) {
            throw new RuntimeException("Caplet " + capsuleClass + " is not compatible with this capsule (" + VERSION + ")");
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<Capsule> getActualCapsuleClass(Class<?> clazz) {
        Class<?> c = clazz;
        while (c != null && !Capsule.class.getName().equals(c.getName()))
            c = c.getSuperclass();
        return (Class<Capsule>) c;
    }

    private static String getCapsuleVersion(Class<?> cls) {
        while (cls != null && !cls.getName().equals(Capsule.class.getName()))
            cls = cls.getSuperclass();
        if (cls == null)
            return null;
        try {
            final Field f = cls.getDeclaredField("VERSION");
            return (String) f.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String toString(ClassLoader cl) {
        return cl == null ? "null"
                : cl.toString() + (cl instanceof URLClassLoader ? ("{" + Arrays.toString(((URLClassLoader) cl).getURLs()) + "}") : "")
                + " --> " + toString(cl.getParent());
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Security">
    /////////// Security ///////////////////////////////////
    private Capsule unsafe(Capsule target) {
        if (target != null) {
            final SecurityManager security = System.getSecurityManager();
            if (security != null && !target.getClass().getProtectionDomain().implies(PERM_UNSAFE_OVERRIDE)) {
                log(LOG_DEBUG, "Unsafe target " + target + " skipped");
                target = null;
            }
        }
        return target;
    }
    //</editor-fold>
}

/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

/**
 * The "Main-Class" of Chionographis library.
 *
 * <p>This class is used only to provide users with meta-information of the library.</p>
 *
 * @since 1.1
 */
public final class Main {

    private Main() {
    }

    /**
     * If the JAR file which contains this class has implementation version information
     * in its manifest, prints it to the standard output stream. Otherwise, prints nothing.
     *
     * @param args
     *      ignored.
     */
    public static void main(String[] args) {
        String implementationVersion = getImplementationVersion();
        if (implementationVersion != null) {
            System.out.println('v' + implementationVersion);
        }
    }

    /**
     * Returns the implementation version of this library.
     * Prefixing with "v" is not performed.
     *
     * @return
     *      the implementation version of this library if supported, {@code null} otherwise.
     */
    static String getImplementationVersion() {
        return Main.class.getPackage().getImplementationVersion();
    }
}

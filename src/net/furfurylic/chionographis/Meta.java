/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.util.AbstractMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.ProjectComponent;

/**
 * A class to instruct the <i>{@linkplain Chionographis}</i> driver to add
 * a processing instruction which includes meta-information of the original source.
 */
public final class Meta extends ProjectComponent {

    /**
     * This type defines the set of usable original source meta-information types.
     *
     * @see Meta#setType(String)
     */
    public enum Type {
        /** The absolute URI. The corresponding string expression is "uri". */
        URI {
            @Override
            Function<java.net.URI, String> extractor(java.net.URI baseURI) {
                return java.net.URI::toString;
            }
        },

        /** The relative URI. The corresponding string expression is "relative-uri". */
        RELATIVE_URI {
            @Override
            Function<java.net.URI, String> extractor(java.net.URI baseURI) {
                return (java.net.URI u) -> baseURI.relativize(u).toString();
            }
         },

        /**
         * The last part of the path of the {@linkplain #URI}.
         * The corresponding string expression is "file-name".
         */
        FILE_NAME {
            @Override
            Function<java.net.URI, String> extractor(java.net.URI baseURI) {
                return createFileNameExtractor();
            }
        },

        /**
         * The substring of the {@linkplain #FILE_NAME} before its last period (".").
         * The corresponding string expression is "file-title".
         */
        FILE_TITLE {
            @Override
            Function<java.net.URI, String> extractor(java.net.URI baseURI) {
                return createFileNameExtractor().andThen(Type::extractFileTitle);
            }
        };

        abstract Function<java.net.URI, String> extractor(java.net.URI baseURI);

        String defaultName() {
            return "chionographis-" + name().toLowerCase().replace('_', '-');
        }

        // /a/b/c.xml -> c.xml
        private static Function<java.net.URI, String> createFileNameExtractor() {
            Pattern pattern = Pattern.compile("([^/]*)/?$");
            return u -> {
                String path = u.getPath();
                Matcher matcher = pattern.matcher(path);
                if (matcher.find()) {
                    return matcher.group(1);
                } else {
                    return "";
                }
            };
        }

        // c.xml -> c
        private static String extractFileTitle(String fileName) {
            int index = fileName.lastIndexOf('.');
            if (index == -1) {
                return fileName;
            } else {
                return fileName.substring(0, index);
            }
        }
    }

    private Type type_ = null;
    private String name_ = null;

    /**
     * Sole constructor.
     */
    Meta() {
    }

    /**
     * Sets the type of the meta-information.
     * Only {@link Type} objects' string expression can be accepted.
     *
     * <p>This is a mandatory attribute.</p>
     *
     * @param type
     *      a {@link Type} object's string expression.
     */
    public void setType(String type) {
        if (type.isEmpty()) {
            throw new BuildException(
                "Empty meta-information type is not acceptable", getLocation());
        } else {
            try {
                type_ = Type.valueOf(type.toUpperCase().replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new BuildException("Bad meta-information type: " + type, getLocation());
            }
        }
    }

    /**
     * Sets the target of the processing instruction.
     *
     * <p>If not specified, the target is defaulted to "chionographis-" and the {@linkplain
     * #setType(String) type} concatenated.</p>
     *
     * @param name
     *      the target of the processing instruction.
     */
    public void setName(String name) {
        if (name.isEmpty()) {
            throw new BuildException(
                "Empty meta-information name is not acceptable", getLocation());
        } else if (name.equalsIgnoreCase("xml")) {
            new BuildException("Bad meta-information name: " + name, getLocation());
        } else {
            name_ = name;
        }
    }

    /**
     * Creates a key-value pair from this object's content.
     *
     * @param baseURI
     *      the base URI to relativize URIs.
     *
     * @return
     *      a key-value pair for this object's content, which shall not be {@code null}.
     *      The key is the meta-information name, and the value is the function which deduces the
     *      string value from the URI of the original source.
     *
     * @throws BuildException
     *      if the {@linkplain #setType(String) type} is not set.
     */
    Map.Entry<String, Function<java.net.URI, String>> yield(java.net.URI baseURI) {
        if (type_ == null) {
            String message = "Incomplete meta-information instruction found";
            if (name_ != null) {
                message += ": name=" + name_;
            }
            throw new BuildException(message, getLocation());
        }

        String name = name_;
        if (name_ == null) {
            name = type_.defaultName();
        }
        return new AbstractMap.SimpleImmutableEntry<>(name, type_.extractor(baseURI));
    }
}

/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import java.net.URI;
import java.util.AbstractMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.LogLevel;

public final class Meta {

    public enum Type {
        URI {
            @Override
            Function<URI, String> extractor() {
                return java.net.URI::toString;
            }
        },

        FILE_NAME {
            @Override
            Function<URI, String> extractor() {
                return createFileNameExtractor();
            }
        },

        FILE_TITLE {
            @Override
            Function<URI, String> extractor() {
                return createFileNameExtractor().andThen(Type::extractFileTitle);
            }
        };

        abstract Function<URI, String> extractor();

        String defaultName() {
            return "chionographis-" + name().toLowerCase().replace('_', '-');
        }

        // /a/b/c.xml -> c.xml
        private static Function<URI, String> createFileNameExtractor() {
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

    private Logger logger_;

    private Type type_ = null;
    private String name_ = null;

    Meta(Logger logger) {
        logger_ = logger;
    }

    public void setType(String type) {
        if (type.isEmpty()) {
            logger_.log(this, "Empty meta-information type is not acceptable", LogLevel.ERR);
            throw new BuildException();
        }

        try {
            type_ = Type.valueOf(type.toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            logger_.log(this, "Bad meta-information type: " + type, LogLevel.ERR);
            throw new BuildException();
        }
    }

    public void setName(String name) {
        if (name.isEmpty()) {
            logger_.log(this, "Empty meta-information name is not acceptable", LogLevel.ERR);
            throw new BuildException();
        }
        if (name.equalsIgnoreCase("xml")) {
            logger_.log(this, "Bad meta-information name: " + name, LogLevel.ERR);
            throw new BuildException();
        }
        name_ = name;
    }

    Map.Entry<String, Function<URI, String>> yield() {
        if (type_ == null) {
            String message = "Incomplete meta-information instruction found";
            if (name_ != null) {
                message += ": name=" + name_;
            }
            logger_.log(this, message, LogLevel.ERR);
            throw new BuildException();
        }

        String name = name_;
        if (name_ == null) {
            name = type_.defaultName();
        }
        return new AbstractMap.SimpleImmutableEntry<>(name, type_.extractor());
    }
}

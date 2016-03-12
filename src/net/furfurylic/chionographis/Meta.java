package net.furfurylic.chionographis;

import java.net.URI;
import java.util.AbstractMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.tools.ant.BuildException;

public final class Meta {

    public enum Type {
        URI, FILE_NAME, FILE_TITLE;

        String defaultName() {
            return "chionographis-" + name().toLowerCase().replace('_', '-');
        }
    }

    private Type type_ = null;
    private String name_ = null;

    Meta() {
    }

    public void setType(String type) {
        type_ = Type.valueOf(type.toUpperCase().replace('-', '_'));
    }

    public void setName(String name) {
        name_ = name;
    }

    Map.Entry<String, Function<URI, String>> yield() {
        if (type_ == null) {
            String message = "Incomplete meta-information instruction found";
            if (name_ != null) {
                message += ": name=" + name_;
            }
            throw new BuildException(message);
        }

        Function<URI, String> extractor;
        switch (type_) {
        case URI:
            extractor = URI::toString;
            break;
        case FILE_NAME:
            extractor = createFileNameExtractor();
            break;
        case FILE_TITLE:
            extractor = createFileNameExtractor().andThen(Meta::extractFileTitle);
            break;
        default:
            assert false;
            extractor = URI::toString;
        }

        String name = name_;
        if (name_ == null) {
            name = type_.defaultName();
        }
        return new AbstractMap.SimpleImmutableEntry<>(name, extractor);
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

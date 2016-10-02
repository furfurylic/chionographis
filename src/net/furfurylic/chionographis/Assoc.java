/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

/**
 * A class used to instruct the <i>{@link Transform}</i> filter to narrow the search of
 * the associated stylesheet to a certain combination of media, title and charcter encoding.
 *
 * @see Transform#createAssoc()
 * @since 1.2
 */
public final class Assoc {
    private String media_;
    private String title_;
    private String charset_;

    Assoc() {
    }

    /**
     * Gives the media for which the referenced stylesheet applies.
     *
     * @param media
     *      a valid media.
     */
    public void setMedia(String media) {
        media_ = media;
    }

    /**
     * Gives the title of the referenced stylesheet in a stylesheet set.
     *
     * @param title
     *      a title.
     */
    public void setTitle(String title) {
        title_ = title;
    }

    /**
     * Gives an advisory character encoding for the referenced stylesheet.
     *
     * @param charset
     *      a valid character encoding.
     */
    public void setCharset(String charset) {
        charset_ = charset;
    }

    String getMedia() {
        return media_;
    }

    String getTitle() {
        return title_;
    }

    String getCharset() {
        return charset_;
    }
}

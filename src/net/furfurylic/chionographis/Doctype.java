/*
 * Chionographis
 *
 * These codes are licensed under CC0.
 * https://creativecommons.org/publicdomain/zero/1.0/deed
 */

package net.furfurylic.chionographis;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.ProjectComponent;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;

/**
 * Instructions to <i>{@link All}</i> and <i>{@link Snip}</i> filters about document type
 * declarations of documents they emit.
 *
 * @see All#createDoctype()
 * @see Snip#createDoctype()
 *
 * @since 1.2
 */
public final class Doctype extends ProjectComponent {

    private String publicID_ = null;
    private String systemID_ = null;

    Doctype() {
    }

    /**
     * Sets the public identifier of the document type.
     *
     * @param publicID
     *      the public identifier.
     */
    public void setPublicID(String publicID) {
        if (!publicID.isEmpty()) {
            publicID_ = publicID;
        }
    }

    /**
     * Sets the system identifier of the document type.
     *
     * @param systemID
     *      the system identifier.
     */
    public void setSystemID(String systemID) {
        if (!systemID.isEmpty()) {
            systemID_ = systemID;
        }
    }

    /**
     * Checks validity of this object.
     *
     * @throws BuildException
     *      if neither {@link #setPublicID(String)} nor {@link #setSystemID(String)} has been
     *      invoked.
     */
    void checkSanity() throws BuildException {
        if ((publicID_ == null) && (systemID_ == null)) {
            throw new BuildException("Neither of IDs are specified", getLocation());
        }
    }

    /**
     * Inserts a {@link DocumentType} node into the specified document
     * only if it does not have one yet.
     *
     * @param document
     *      a {@link Document} into which a {@link DocumentType} node is embedded.
     */
    void populateInto(Document document) {
        if (document.getDoctype() == null) {
            DocumentType doctype = document.getImplementation().createDocumentType(
                    document.getDocumentElement().getNodeName(), publicID_, systemID_);
            document.insertBefore(doctype, document.getFirstChild());
        }
    }
}

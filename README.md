# Chionographis

[![Build Status](https://travis-ci.org/furfurylic/chionographis.svg?branch=master)](https://travis-ci.org/furfurylic/chionographis)

Chionographis is an [Apache Ant](http://ant.apache.org/) task for cascading XML transformation.
This transformation is done with following three primitive operations applied arbitrary number of times in arbitrary order:
 - XML transformation with XSLT stylesheet,
 - joining of multiple XML documents into one,
 - and snipping fragments from XML documents.

## Requirements

Running Chionographis requires JRE installed, of version 1.8 or later. And it also requires [Apache Ant](http://ant.apache.org/) installed, of version 1.8.0 or later.

To build Chionographis, you need JDK instead of JRE. Optionally, to build User's Guide, you need [AsciiDoc](http://www.methods.co.nz/asciidoc/) installed too.

## How to Build

To build Chionographis, with `ant` command in your `PATH`, at the top directory simply run

```
$ ant
```
and JAR files will be generated in `release/lib` directory.

If you want API documents, similarly run

```
$ ant doc
```

and you will get API documents in `release/doc` directory.

## Example

Here is an example of a Ant target which uses Chionographis:

```XML
<target name="flora-genera">
  <chionographis srcdir="flora" includes="*.xml">
    <all root="flora">
      <transform style="flora/to-genera.xsl">
        <snip select="/genera/genus">
          <output destdir="flora-genera" refer="/genus/@name">
            <globmapper from="*" to="*.xml"/>
          </output>
        </snip>
      </transform>
    </all>
  </chionographis>
</target>
```

This target reads all `.xml` files in `flora` directory and collects all of them into a new document `<flora>...</flora>`,
then transforms it with XSLT stylesheet `flora/to-genera.xsl`,
then finds all document fragemnts which matches XPath `/genera/genus`,
and then finally writes all fragments into separated files in directory `flora-genera` as `XXX.xml`, where `XXX` is the `name` attribute of the document element of each document fragment.

For details, please consult the Users Guide.

## License

These codes are licensed under [CC0](https://creativecommons.org/publicdomain/zero/1.0/deed).

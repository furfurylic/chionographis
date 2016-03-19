# Chionographis

Chionographis is an [Apache Ant](http://ant.apache.org/) task for cascading XML transformation.
This transformation is done with following three primitive operations applied arbitrary number of times in arbitrary order:
 - XML transformation with XSLT stylesheet,
 - joining of multiple XML documents into one,
 - and snipping fragments from XML documents.

For usage, consult [User's Guide](UsersGuide.asciidoc). For requirements and the ways to build, read below.

## Requirements

Running Chionographis requires JRE installed, of version 1.8 or later. And it also requires [Apache Ant](http://ant.apache.org/) installed, of version 1.8.0 or later.

To build Chionographis, you need JDK instead of JRE.

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

## License

These codes are licensed under [CC0](https://creativecommons.org/publicdomain/zero/1.0/deed).

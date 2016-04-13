# Chionographis

[![Build Status](https://travis-ci.org/furfurylic/chionographis.svg?branch=master)](https://travis-ci.org/furfurylic/chionographis)

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

## How to Run

To run Chionographis with Ant, you have to define the task:

```XML
<taskdef name="chionographis"
         classname="net.furfurylic.chionographis.Chionographis"/>
```

Then you can write a target which uses this task:

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

You can run this target with `ant` command:

```
$ ant -lib the-directory-which-contains-chionographis.jar -f sample.xml flora-genera
```

This sample does following:

 1. first reads and parses all files with `.xml` extension in `flora` directory,
 1. then collects all parsed documents into a new document whose document element is `<flora>...</flora>`,
 1. then transforms this document with XSLT stylesheet `flora/to-genera.xsl`,
 1. then finds all fragments from the resulted document which matches XPath criteria `/genera/genus`,
 1. finally writes each fragment to a file whose path is `flora-genera/XXX.xml`, where `XXX` is the string value of XPath expression `/genus/@name` of each fragment document (that is, `XXX` of the document element `<genus name="XXX">...</genus>`).

Additionally, it might be noteworthy that:

 - this target recognizes whether the output files are up to date for the input files, and, if so, cuts out the file output process,
 - and if the runtime environment has multiple available processors, this target tries to take advantage of them to parallelize the reading and writing of the files.

For details, please consult the [Users Guide](UsersGuide.asciidoc).

## License

These codes are licensed under [CC0](https://creativecommons.org/publicdomain/zero/1.0/deed).

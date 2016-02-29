# Chionographis User's Guide

Chionographis is an Ant task that performs cascading XML transformation.

# Drivers, sinks and filters

In Chionographis, cascading XML processing is accomplished by coordination of _actors_,
each of which represents one XML processing. Actors are classified into three caterogies:
 _drivers_, _sinks_ and _filters_.

| Category | Description | Example |
|:-- |:-- |:-- |
|_Driver_|Passes XML documents to _sinks_ it holds.|_Chionographis_, _Transform_, _All_ and _Snip_|
|_Sink_|Receives XML documents from its _driver_.|_Output_, _Transform_, _All_ and _Snip_|
|_Filter_|Is a _driver_ and a _sink_.|_Transform_, _All_ and _Snip_|

Chionographis has five actors synopsized below:

| Actor | Category | Synopsis |
|:-- |:-- |:-- |
| _Chionographis_ |Driver|Reads XML documents from external sources (called _original_ sources) and passes them to its sinks.|
| _Transform_ |Filter|Receives XML documents from its driver, transforms them by an XSLT stylesheet, and passes them to its sinks.|
| _All_ |Filter|Receives XML documents from its driver, collects all of them into a new XML documents, and passes it to its sinks.|
| _Snip_ |Filter|Receives XML documents from its driver, extracts all document fragments matching an XPath expression, and passes them to its sinks with each fragment being one document.|
| _Output_ |Sink|Receives XML documents from its driver and writes them into files.|

----
## _Chionographis_ driver

A _Chionographis_ driver reads external XML documents from files and emits them into sinks it has.
As of now, the sources of XML documents (called _original_ sources) are files only.

This is an Ant task too, and can be configured to read documents from various sources with
Ant framework. For example, it can have filesets and exclude patterns of Ant.
For full set of its ability to accept the source information, consult Ant's document
(this task extends from _MatchingTask_, which is a common base class of Ant's core tasks, e.g. Javac and Delete.)

### Attributes
| Attribute | Description | Required |
|:-- |:-- |:-- |
|basedir|The base directory of this task.| No; defaults to the project's base directory|
|srcdir|The source directory. If not absolute, will be resolved by the base directory of the task.| No: defaults to the task's base directory|
|force|Whether this driver proceed to process even if existing output files seem new enough.| No; defaults to `no`|
|cache|Whether this driver uses a document cache shared in Chionographis library. When set to `yes`, the performance will be greatly increased. This option is here to evade a certain XSLT processor's bug.| No; defaults to `no`|
|Other _MatchingTask_'s attributes|Please consult Ant's document.|No|

### Nested elements

| Element | Description | Required |
|:-- |:-- |:-- |:-- |
|namespace|A pair of a namespace prefix and a namespace name which is mapped from the prefix. This mapping is used by task's child elements "transform", "all" and "snip".| No |
|transform|A _Transform_ sink.| No; at least one sink required |
|all|An _All_ sink.| No; at least one sink required |
|snip|A _Snip_ sink.| No; at least one sink required |
|output|An _Output_ sink.| No; at least one sink required |
|Other _MatchingTask_'s nested elements|Please consult Ant's document.|No|

#### Namespace's attributes / text content

| Attribute | Description | Required |
|:-- |:-- |:-- |:-- |
|prefix|The prefix.| Yes |
|(text)|The namespace name (URI) mapped from the prefix. | Yes |

----
## _Transform_ filter

A _Transform_ filter receives XML documents, and apply transformation by an XSLT stylesheet,
generates output XML documents one per one input document and pass them to sinks it has.

### Attributes

| Attribute | Description | Required |
|:-- |:-- |:-- |
|style|The file path of the XSLT stylesheet. If not absolute, will be resolved by the base directory of the task.| Yes |
|force|Whether this filter proceed to process even if existing output files seem new enough.| No; defaults to `no`|
|cache|Whether this driver uses a document cache shared in Chionographis library. When set to `yes`, the performance will be greatly increased. This option is here to evade a certain XSLT processor's bug.| No; defaults to `no`|

### Nested elements

| Element | Description | Required |
|:-- |:-- |:-- |
|param|A key-value pair of stylesheet parameter. As of now, only string parameter values are supported.| No |
|transform|A _Transform_ sink.| No; at least one sink required |
|all|An _All_ sink.| No; at least one sink required |
|snip|A _Snip_ sink.| No; at least one sink required |
|output|An _Output_ sink.| No; at least one sink required |

#### Param's attributes

| Attribute | Description | Required |
|:-- |:-- |:-- |
|name|The name of the stylesheet parameter. Supported forms are `localName`, `prefix:localName` and `{namespaceURI}localName`. In first form, the name doesn't belong to any namespace. In second form, the name belongs to a namespace whose name is mapped from prefix using the task's child _namespace_ elements.| Yes |
|value|The value of the stylesheet parameter. | Yes |

----
## _All_ filter

An _All_ filter receives XML documents, collects all of their document elements,
arranges them as child elements of a newly-created XML document's document element,
and passes the resulted document to sinks it has.
The number of document passed to the sinks is always one.

### Attributes

| Attribute | Description | Required |
|:-- |:-- |:-- |
|root|The name of the document element of the resulted document. Supported forms are `localName`, `prefix:localName` and `{namespaceURI}localName`. In first form, the name doesn't belong to any namespace. In second form, the name belongs to a namespace whose name is mapped from the prefix using the task's child _namespace_ elements.| Yes |
|force|Whether this filter proceed to process even if existing output files seem new enough.| No; defaults to `yes` (note (A))|

Note (A): If the set of the original source documents is constant (regardless of whether each
document's content is modified), setting _force_ to `no` is generally safe.
Otherwise, setting _force_ to `yes` is possibly dangerous because the _All_
filter can overlook the possible changes in resulted document when source
documents are added or removed. This is why the default value is `yes`.

### Nested elements

| Element | Description | Required |
|:-- |:-- |:-- |:-- |
|transform|A _Transform_ sink.| No; at least one sink required |
|all|An _All_ sink.| No; at least one sink required |
|snip|A _Snip_ sink.| No; at least one sink required |
|output|An _Output_ sink.| No; at least one sink required |

----
## _Snip_ filter

### Attributes

| Attribute | Description | Required |
|:-- |:-- |:-- |
|select|An XPath expression which specifies the unit in which the source document is snipped. It can include include names which belong some namespaces only when the namespaces are denoted by prefixes defined in the task's child _namespace_ elements.| Yes |

### Nested elements

| Element | Description | Required |
|:-- |:-- |:-- |:-- |
|transform|A _Transform_ sink.| No; at least one sink required |
|all|An _All_ sink.| No; at least one sink required |
|snip|A _Snip_ sink.| No; at least one sink required |
|output|An _Output_ sink.| No; at least one sink required |

----
## _Output_ sink

### Attributes

| Attribute | Description | Required |
|:-- |:-- |:-- |
|destdir|The destination directory. If not absolute, will be resolved by the base directory of the task.| No; defaults to the task's base directory |
|dest|The destination file path. If not absolute, will be resolved by the destination directory.| No; note (A) (C) |
|refer|An XPath expression which points the content of "source document" (see below) required to decide the output file path. The string value of the pointee is used as an input to the installed file mapper if any, otherwise is used as if it is set to _dest_ attribute. _Transform_ drivers retrieve the pointee from the source documents of the transformation; on the other hand, the _Chionographis_ and _Snip_ drivers retrieve from their result document (the source document of this sink) and _All_ driver retrieves none. The XPath expression can include include names which belong some namespaces only when the namespaces are denoted by prefixes defined in the task's child _namespace_ elements.| No; note (B) (C) |
|mkdirs|Whether this sink creates parent directories of the destination file if needed.| No; defaults to `no`|
|force|Whether this sink creates output files even if existing files seem new enough.| No; defaults to `no`|

### Nested elements

| Element | Description | Required |
|:-- |:-- |:-- |:-- |
|file mappers|A mapper which makes output file names from extracted source document content pointed by _refer_ attribute if specified, otherwise from original source file names.| No; note (C) (D) |

### Notes

Note (A): _dest_ and file mappers can be specified exclusively.

Note (B): _dest_ and _refer_ can be specified exclusively.

Note (C): At least one of _dest_, _refer_ and a file mapper must be specified.

Note (D): At most one file mapper can be installed .

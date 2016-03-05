<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">
<xsl:output method="text"/>
<xsl:template match="*">[<xsl:call-template name="write-node-name"/><xsl:apply-templates select="@*"><xsl:sort select="namespace-uri(.)"/><xsl:sort select="local-name(.)"/></xsl:apply-templates>:<xsl:apply-templates select="node()"/>]</xsl:template>
<xsl:template match="@*">(<xsl:call-template name="write-node-name"/>=<xsl:value-of select="."/>)</xsl:template>
<xsl:template match="@*[starts-with(local-name(), 'xml')]"/>
<xsl:template match="text()"><xsl:value-of select="."/></xsl:template>
<xsl:template match="processing-instruction()">&lt;<xsl:value-of select="local-name()"/>=<xsl:value-of select="."/>&gt;</xsl:template>
<xsl:template match="processing-instruction('chionographis-output')"/>
<xsl:template name="write-node-name"><xsl:if test="namespace-uri(.)">{<xsl:value-of select="namespace-uri(.)"/>}</xsl:if><xsl:value-of select="local-name(.)"/></xsl:template>
</xsl:stylesheet>

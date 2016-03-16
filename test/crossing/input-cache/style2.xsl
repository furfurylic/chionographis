<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">
<xsl:import href="base.xsl"/>
<xsl:template match="/">***<xsl:apply-imports/>***</xsl:template>
<xsl:template match="*"><xsl:apply-templates select="processing-instruction()"/><xsl:apply-imports/></xsl:template>
<xsl:template match="processing-instruction()">&lt;<xsl:value-of select="local-name(.)"/>=<xsl:value-of select="."/>&gt;</xsl:template>
</xsl:stylesheet>

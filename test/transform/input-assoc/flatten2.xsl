<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">
<xsl:import href="flatten.xsl"/>
<xsl:param name="p"/>
<xsl:template match="/"><xsl:copy-of select="$p"/><xsl:apply-imports/><xsl:copy-of select="$p"/></xsl:template>
<xsl:template match="processing-instruction()"/>
</xsl:stylesheet>

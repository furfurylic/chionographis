<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">
<xsl:import href="../../flatten.xsl"/>
<xsl:param name="p1"/>
<xsl:param name="p2"/>
<xsl:template match="/"><xsl:value-of select="$p1"/><xsl:apply-imports/><xsl:value-of select="$p2"/></xsl:template>
</xsl:stylesheet>

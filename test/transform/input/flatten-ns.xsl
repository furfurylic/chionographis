<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0" xmlns:x="http://www.furfurylic.net/chionographis/test/transform/ns">
<xsl:import href="../../flatten.xsl"/>
<xsl:param name="x:p1"/>
<xsl:param name="x:p2"/>
<xsl:template match="/"><xsl:value-of select="$x:p1"/><xsl:apply-imports/><xsl:value-of select="$x:p2"/></xsl:template>
</xsl:stylesheet>

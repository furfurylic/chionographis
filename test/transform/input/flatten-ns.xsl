<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0" xmlns:x1="http://www.furfurylic.net/chionographis/test/transform/ns/1" xmlns:x2="http://www.furfurylic.net/chionographis/test/transform/ns/2">
<xsl:import href="../../flatten.xsl"/>
<xsl:param name="x1:p"/>
<xsl:param name="x2:p"/>
<xsl:template match="/"><xsl:value-of select="$x1:p"/><xsl:apply-imports/><xsl:value-of select="$x2:p"/></xsl:template>
</xsl:stylesheet>

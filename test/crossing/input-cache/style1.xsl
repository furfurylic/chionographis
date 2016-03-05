<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">
<xsl:include href="base.xsl"/>
<xsl:variable name="external" select="document('external.xml')"/>
<xsl:template match="/"><xsl:apply-templates select="$external//a"/></xsl:template>
</xsl:stylesheet>

<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">
<xsl:output method="text"/>
<xsl:template match="*"><xsl:value-of select="local-name(.)"/>=<xsl:value-of select="."/></xsl:template>
</xsl:stylesheet>

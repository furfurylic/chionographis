<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">
<xsl:import href="flatten.xsl"/>
<xsl:template match="/">__<xsl:apply-imports/>__</xsl:template>
<xsl:template match="processing-instruction()"/>
</xsl:stylesheet>

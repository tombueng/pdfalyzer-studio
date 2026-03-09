# ═══════════════════════════════════════════════════════════════════════════════
# PDF Digital Signatures — Book Build Environment
#
# All diagrams are TikZ (rendered natively by LuaLaTeX).
# Pipeline: AsciiDoc → DocBook → pandoc → LaTeX → LuaLaTeX → PDF
# Also: TikZ standalone → SVG (for HTML/Markdown export)
# ═══════════════════════════════════════════════════════════════════════════════
FROM pandoc/extra:latest-ubuntu

# ── System packages ──────────────────────────────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
    ruby ruby-dev build-essential \
    pdf2svg \
    fonts-noto fonts-noto-cjk fonts-liberation fonts-dejavu \
    && rm -rf /var/lib/apt/lists/*

# ── Missing TeX packages (not in pandoc/extra base) ─────────────────────────
RUN tlmgr --repository=https://ftp.math.utah.edu/pub/tex/historic/systems/texlive/2025/tlnet-final \
    install pdfcol forest pgf-umlsd bytefield standalone \
    pdftexcmds infwarerr ltxcmds currfile filehook luatex85 tocloft

# ── Asciidoctor (AsciiDoc → DocBook conversion) ─────────────────────────────
RUN gem install asciidoctor rouge --no-document

# ── Verify tools ─────────────────────────────────────────────────────────────
RUN asciidoctor --version && pandoc --version | head -1 && lualatex --version | head -1

WORKDIR /book
ENTRYPOINT ["/book/scripts/build-book.sh"]

#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# PDF Digital Signatures — Book Build Pipeline
#
# Usage:
#   ./scripts/build-book.sh              # Build PDF (default)
#   ./scripts/build-book.sh pdf          # Build PDF only
#   ./scripts/build-book.sh html         # Build HTML only
#   ./scripts/build-book.sh svg          # Export TikZ diagrams to SVG
#   ./scripts/build-book.sh all          # Build everything
#
# Pipeline:
#   1. AsciiDoc → DocBook XML  (asciidoctor)
#   2. DocBook  → LaTeX        (pandoc + custom preamble)
#   3. LaTeX    → PDF          (lualatex, 2-3 passes for TOC/refs)
#
# For HTML/Markdown: TikZ diagrams are pre-rendered to SVG via standalone class.
# ═══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

BOOK_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="${BOOK_DIR}/target/book"
DOCBOOK_DIR="${BUILD_DIR}/docbook"
LATEX_DIR="${BUILD_DIR}/latex"
PDF_DIR="${BUILD_DIR}/pdf"
HTML_DIR="${BUILD_DIR}/html"
SVG_DIR="${BUILD_DIR}/svg"

TARGET="${1:-pdf}"

log() { echo "── $1"; }

# ─────────────────────────────────────────────────────────────────────────────
# Step 0: Compile TikZ standalone diagrams → PDF
# ─────────────────────────────────────────────────────────────────────────────
build_diagrams() {
    local TIKZ_DIR="${BOOK_DIR}/diagrams/tikz"
    local OUT_DIR="${BOOK_DIR}/diagrams/pdf"

    if [ ! -d "${TIKZ_DIR}" ] || [ -z "$(ls -A "${TIKZ_DIR}"/*.tex 2>/dev/null)" ]; then
        log "No TikZ diagrams to compile"
        return 0
    fi

    log "Compiling TikZ diagrams → PDF"
    mkdir -p "${OUT_DIR}"

    for texfile in "${TIKZ_DIR}"/*.tex; do
        local name="$(basename "${texfile}" .tex)"
        local outpdf="${OUT_DIR}/${name}.pdf"

        # Only recompile if source is newer than output
        if [ "${texfile}" -nt "${outpdf}" ] 2>/dev/null || [ ! -f "${outpdf}" ]; then
            log "  Compiling ${name}.tex"
            lualatex \
                -interaction=nonstopmode \
                -output-directory="${OUT_DIR}" \
                "${texfile}" > /dev/null 2>&1 || {
                log "  WARNING: Failed to compile ${name}.tex"
            }
            # Clean up aux files
            rm -f "${OUT_DIR}/${name}.aux" "${OUT_DIR}/${name}.log"
        fi
    done

    log "Diagrams compiled to ${OUT_DIR}/"
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: AsciiDoc → DocBook
# ─────────────────────────────────────────────────────────────────────────────
build_docbook() {
    log "AsciiDoc → DocBook"
    mkdir -p "${DOCBOOK_DIR}"
    asciidoctor \
        -b docbook5 \
        -d book \
        -a leveloffset=+0 \
        -o "${DOCBOOK_DIR}/book.xml" \
        "${BOOK_DIR}/book.adoc"
    log "DocBook written to ${DOCBOOK_DIR}/book.xml"
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: DocBook → LaTeX
# ─────────────────────────────────────────────────────────────────────────────
build_latex() {
    log "DocBook → LaTeX"
    mkdir -p "${LATEX_DIR}"

    pandoc \
        "${DOCBOOK_DIR}/book.xml" \
        -f docbook \
        -t latex \
        --standalone \
        --top-level-division=chapter \
        --pdf-engine=lualatex \
        --include-in-header="${BOOK_DIR}/latex/preamble.tex" \
        --highlight-style=breezedark \
        --toc \
        --toc-depth=3 \
        --number-sections \
        -V documentclass=book \
        -V papersize=a4 \
        -V geometry="inner=1in,outer=0.75in,top=0.85in,bottom=1in" \
        -V fontsize=11pt \
        -V classoption=openright \
        -V colorlinks=true \
        -V linkcolor=accent \
        -V urlcolor=linkcolor \
        -o "${LATEX_DIR}/book.tex"

    # Copy diagram resources (SVGs, etc.) if present
    if [ -d "${BOOK_DIR}/diagrams" ]; then
        cp -r "${BOOK_DIR}/diagrams/" "${LATEX_DIR}/diagrams/" 2>/dev/null || true
    fi

    log "LaTeX written to ${LATEX_DIR}/book.tex"
}

# ─────────────────────────────────────────────────────────────────────────────
# Step 3: LaTeX → PDF
# ─────────────────────────────────────────────────────────────────────────────
build_pdf() {
    log "LaTeX → PDF (LuaLaTeX, up to 3 passes)"
    mkdir -p "${PDF_DIR}"

    # Pass 1: generate .aux, .toc
    lualatex \
        -interaction=nonstopmode \
        -output-directory="${LATEX_DIR}" \
        "${LATEX_DIR}/book.tex" || true

    # Pass 2: resolve references
    lualatex \
        -interaction=nonstopmode \
        -output-directory="${LATEX_DIR}" \
        "${LATEX_DIR}/book.tex" || true

    # Pass 3: only if labels changed (e.g. after adding new cross-reference anchors)
    if grep -q "Label(s) may have changed" "${LATEX_DIR}/book.log" 2>/dev/null; then
        log "  Labels changed — running pass 3"
        lualatex \
            -interaction=nonstopmode \
            -output-directory="${LATEX_DIR}" \
            "${LATEX_DIR}/book.tex" || true
    fi

    if [ -f "${LATEX_DIR}/book.pdf" ]; then
        # Determine next version number from existing files
        local last_ver=0
        for f in "${PDF_DIR}"/pdf-digital-signatures-v*.pdf; do
            [ -f "$f" ] || continue
            local num="${f##*-v}"
            num="${num%.pdf}"
            if [ "$num" -gt "$last_ver" ] 2>/dev/null; then
                last_ver="$num"
            fi
        done
        local next_ver=$((last_ver + 1))
        local versioned="pdf-digital-signatures-v${next_ver}.pdf"

        cp "${LATEX_DIR}/book.pdf" "${PDF_DIR}/${versioned}"
        log "PDF written to ${PDF_DIR}/${versioned}"
    else
        log "ERROR: No PDF produced. Check ${LATEX_DIR}/book.log"
        exit 1
    fi
}

# ─────────────────────────────────────────────────────────────────────────────
# TikZ → SVG export (for HTML/Markdown embedding)
# ─────────────────────────────────────────────────────────────────────────────
build_svg() {
    log "Extracting TikZ diagrams → SVG"
    mkdir -p "${SVG_DIR}"

    # Extract all TikZ blocks from .adoc files and render each to SVG
    # Uses the standalone documentclass so each diagram is a self-contained page
    java "${BOOK_DIR}/scripts/TikzExtractor.java" \
        "${BOOK_DIR}/chapters" \
        "${SVG_DIR}" \
        "${BOOK_DIR}/latex/preamble.tex"

    log "SVGs written to ${SVG_DIR}/"
}

# ─────────────────────────────────────────────────────────────────────────────
# HTML build (AsciiDoc direct, with pre-rendered SVGs for diagrams)
# ─────────────────────────────────────────────────────────────────────────────
build_html() {
    log "AsciiDoc → HTML5"
    mkdir -p "${HTML_DIR}"

    # First render SVGs if not done
    if [ ! -d "${SVG_DIR}" ] || [ -z "$(ls -A "${SVG_DIR}" 2>/dev/null)" ]; then
        build_svg
    fi

    # Copy SVGs to HTML output dir
    mkdir -p "${HTML_DIR}/diagrams"
    cp "${SVG_DIR}/"*.svg "${HTML_DIR}/diagrams/" 2>/dev/null || true

    asciidoctor \
        -b html5 \
        -d book \
        -a source-highlighter=rouge \
        -a rouge-style=monokai \
        -a icons=font \
        -a toc=left \
        -a toclevels=3 \
        -a sectnums \
        -a imagesdir=diagrams \
        -o "${HTML_DIR}/index.html" \
        "${BOOK_DIR}/book.adoc"

    log "HTML written to ${HTML_DIR}/index.html"
}

# ─────────────────────────────────────────────────────────────────────────────
# Dispatch
# ─────────────────────────────────────────────────────────────────────────────
case "${TARGET}" in
    pdf)
        build_diagrams
        build_docbook
        build_latex
        build_pdf
        ;;
    html)
        build_html
        ;;
    svg)
        build_svg
        ;;
    latex|tex)
        build_docbook
        build_latex
        ;;
    all)
        build_diagrams
        build_docbook
        build_latex
        build_pdf
        build_svg
        build_html
        ;;
    *)
        echo "Usage: $0 {pdf|html|svg|latex|all}"
        exit 1
        ;;
esac

log "Done."

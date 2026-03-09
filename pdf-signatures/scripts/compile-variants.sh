#!/bin/bash
set -u
mkdir -p /book/target/test
cd /tmp
export TEXINPUTS="/book//:"

for PART in 1 2 3 4; do
    echo "=== Compiling part ${PART} ==="
    lualatex -interaction=nonstopmode "/book/test-dict-v2-part${PART}.tex" > "/tmp/part${PART}.log" 2>&1 || true
    PDFFILE="test-dict-v2-part${PART}.pdf"
    if [ -f "$PDFFILE" ]; then
        cp "$PDFFILE" /book/target/test/
        FSIZE=$(stat -c%s "$PDFFILE")
        echo "Part ${PART}: OK - ${FSIZE} bytes"
    else
        echo "Part ${PART}: FAILED"
        tail -30 "/tmp/part${PART}.log"
    fi
done

# Also compile standalone files
for FILE in test-dict-tables test-dict-v19-fixed; do
    echo "=== Compiling ${FILE} ==="
    cd /tmp
    lualatex -interaction=nonstopmode "/book/${FILE}.tex" > "/tmp/${FILE}.log" 2>&1 || true
    if [ -f "${FILE}.pdf" ]; then
        cp "${FILE}.pdf" /book/target/test/
        FSIZE=$(stat -c%s "${FILE}.pdf")
        echo "${FILE}: OK - ${FSIZE} bytes"
    else
        echo "${FILE}: FAILED"
        grep -i 'error\|fatal\|missing' "/tmp/${FILE}.log" | head -5
    fi
done

echo "---"
ls -la /book/target/test/*.pdf 2>/dev/null

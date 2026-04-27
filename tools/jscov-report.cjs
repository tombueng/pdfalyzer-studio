#!/usr/bin/env node
/*
 * Convert raw V8 precise-coverage JSON dumps captured from ChromeDriver
 * (target/js-coverage/raw/coverage-*.json) into LCOV + HTML using
 * monocart-coverage-reports.
 *
 * The LCOV file (target/js-coverage/lcov.info) is uploaded to Codecov
 * with the `frontend` flag so it is reported separately from JaCoCo.
 *
 * Usage:  node tools/jscov-report.cjs
 */

const fs = require('node:fs');
const path = require('node:path');

const RAW_DIR = path.resolve(__dirname, '..', 'target', 'js-coverage', 'raw');
const REPORT_DIR = path.resolve(__dirname, '..', 'target', 'js-coverage', 'report');
const STATIC_ROOT = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'static');

if (!fs.existsSync(RAW_DIR)) {
    console.warn(`No raw V8 coverage at ${RAW_DIR} — skipping JS coverage report.`);
    process.exit(0);
}

let MCR;
try {
    MCR = require('monocart-coverage-reports');
} catch (err) {
    console.error('monocart-coverage-reports not installed. Run: npm i -D monocart-coverage-reports');
    process.exit(1);
}

const mcr = MCR({
    name: 'PDFalyzer Studio – Frontend Coverage',
    outputDir: REPORT_DIR,
    reports: ['lcovonly', 'v8', 'html', 'console-summary'],
    sourcePath: (filePath) => {
        const url = filePath.replace(/^https?:\/\/[^/]+/, '');
        const local = path.join(STATIC_ROOT, url);
        return fs.existsSync(local) ? path.relative(process.cwd(), local) : filePath;
    },
    entryFilter: (entry) => {
        const url = entry.url || '';
        if (!url.startsWith('http')) return false;
        if (url.includes('/webjars/') || url.includes('/license/')) return false;
        const p = new URL(url).pathname;
        return p.endsWith('.js') || p.endsWith('.mjs');
    },
    sourceFilter: (sourcePath) => {
        return sourcePath.includes('/static/') || sourcePath.includes('src/main/resources/static');
    },
});

(async () => {
    const files = fs.readdirSync(RAW_DIR).filter((f) => f.endsWith('.json'));
    if (files.length === 0) {
        console.warn(`No coverage JSON in ${RAW_DIR} — nothing to report.`);
        process.exit(0);
    }
    for (const f of files) {
        const raw = JSON.parse(fs.readFileSync(path.join(RAW_DIR, f), 'utf8'));
        await mcr.add(raw.result || raw);
    }
    const report = await mcr.generate();
    console.log(`JS coverage: lines ${report.summary.lines.pct}% / functions ${report.summary.functions.pct}%`);

    // Emit a flat lcov.info next to report dir for easy upload.
    const lcovSrc = path.join(REPORT_DIR, 'lcov.info');
    const lcovDst = path.resolve(__dirname, '..', 'target', 'js-coverage', 'lcov.info');
    if (fs.existsSync(lcovSrc)) {
        fs.copyFileSync(lcovSrc, lcovDst);
        console.log(`Wrote ${lcovDst}`);
    }
})().catch((err) => {
    console.error(err);
    process.exit(1);
});

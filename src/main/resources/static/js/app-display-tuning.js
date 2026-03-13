/* === PDFalyzer Studio -- Display Tuning ===
 * Panel with sliders for brightness, contrast, saturation, text readability,
 * and accent intensity. Persists to localStorage. Applied via CSS custom
 * properties and surgical color recalculation.
 */
(function () {
    'use strict';

    var STORAGE_KEY = 'pdfalyzer-display-tuning';

    /* ── Slider definitions ────────────────────────────────────────────
     *  id           : localStorage sub-key & CSS property suffix
     *  label        : UI label
     *  icon         : Font Awesome icon
     *  min/max/step : range input attributes
     *  defaultVal   : default value
     *  unit         : display suffix
     *  tooltip      : description shown on hover
     * ────────────────────────────────────────────────────────────────── */
    /* ── Slider groups ─────────────────────────────────────────────── */
    var UI_GROUP = {
        label: 'Interface',
        icon: 'fa-desktop',
        sliders: [
            {
                id: 'brightness', label: 'Brightness', icon: 'fa-sun',
                min: 0.7, max: 1.4, step: 0.02, defaultVal: 1.0, unit: '',
                tooltip: 'Overall UI brightness (does not affect PDF rendering)'
            },
            {
                id: 'contrast', label: 'Contrast', icon: 'fa-circle-half-stroke',
                min: 0.7, max: 1.5, step: 0.02, defaultVal: 1.0, unit: '',
                tooltip: 'Boosts contrast between text and background'
            },
            {
                id: 'saturation', label: 'Saturation', icon: 'fa-droplet',
                min: 0.0, max: 2.0, step: 0.05, defaultVal: 1.0, unit: '',
                tooltip: 'Color vividness -- 0 = greyscale, 2 = hyper-saturated'
            },
            {
                id: 'textBoost', label: 'Text Readability', icon: 'fa-font',
                min: 0, max: 50, step: 2, defaultVal: 0, unit: '%',
                tooltip: 'Pushes muted text closer to full brightness for easier reading'
            },
            {
                id: 'accentBright', label: 'Accent Glow', icon: 'fa-wand-magic-sparkles',
                min: 0.7, max: 1.5, step: 0.02, defaultVal: 1.0, unit: '',
                tooltip: 'Brightens or dims the accent/brand color'
            }
        ]
    };

    var DOC_GROUP = {
        label: 'Document',
        icon: 'fa-file-pdf',
        sliders: [
            {
                id: 'docBrightness', label: 'Brightness', icon: 'fa-sun',
                min: 0.5, max: 2.0, step: 0.02, defaultVal: 1.0, unit: '',
                tooltip: 'Brighten or dim the PDF pages -- useful for faded scans'
            },
            {
                id: 'docContrast', label: 'Contrast', icon: 'fa-circle-half-stroke',
                min: 0.5, max: 3.0, step: 0.05, defaultVal: 1.0, unit: '',
                tooltip: 'Sharpen text against background -- great for low-contrast documents'
            },
            {
                id: 'docSaturation', label: 'Saturation', icon: 'fa-droplet',
                min: 0.0, max: 3.0, step: 0.05, defaultVal: 1.0, unit: '',
                tooltip: 'Color intensity -- 0 = greyscale, >1 = vivid'
            },
            {
                id: 'docGrayscale', label: 'Grayscale', icon: 'fa-palette',
                min: 0, max: 100, step: 5, defaultVal: 0, unit: '%',
                tooltip: 'Strip color from pages -- useful for printing or readability'
            },
            {
                id: 'docInvert', label: 'Invert', icon: 'fa-moon',
                min: 0, max: 100, step: 5, defaultVal: 0, unit: '%',
                tooltip: 'Invert page colors -- 100% = full dark mode reading'
            },
            {
                id: 'docSepia', label: 'Sepia', icon: 'fa-scroll',
                min: 0, max: 100, step: 5, defaultVal: 0, unit: '%',
                tooltip: 'Warm parchment tone -- reduces eye strain on bright pages'
            },
            {
                id: 'docHueRotate', label: 'Hue Rotate', icon: 'fa-rotate',
                min: 0, max: 360, step: 5, defaultVal: 0, unit: 'deg',
                tooltip: 'Shift all colors on the color wheel -- combine with invert for custom dark modes'
            }
        ]
    };

    var GROUPS = [UI_GROUP, DOC_GROUP];
    var ALL_SLIDERS = UI_GROUP.sliders.concat(DOC_GROUP.sliders);

    /* ── State ──────────────────────────────────────────────────────── */
    function loadState() {
        try {
            var raw = localStorage.getItem(STORAGE_KEY);
            return raw ? JSON.parse(raw) : {};
        } catch (e) { return {}; }
    }

    function saveState(state) {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    }

    function getVal(state, slider) {
        return state[slider.id] !== undefined ? Number(state[slider.id]) : slider.defaultVal;
    }

    /* ── Color helpers ─────────────────────────────────────────────── */
    function parseColor(str) {
        // Use a temporary element to resolve CSS var / color-mix values
        var el = document.createElement('div');
        el.style.color = str;
        document.body.appendChild(el);
        var computed = getComputedStyle(el).color;
        document.body.removeChild(el);
        var m = computed.match(/(\d+)/g);
        if (!m || m.length < 3) return [224, 224, 224];
        return [parseInt(m[0], 10), parseInt(m[1], 10), parseInt(m[2], 10)];
    }

    function getCssVar(name) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    }

    function rgbToHex(r, g, b) {
        return '#' + [r, g, b].map(function (c) {
            var h = Math.max(0, Math.min(255, Math.round(c))).toString(16);
            return h.length < 2 ? '0' + h : h;
        }).join('');
    }

    function mixColors(c1, c2, pct) {
        // pct = percentage of c1 (0..100)
        var f = pct / 100;
        return [
            c1[0] * f + c2[0] * (1 - f),
            c1[1] * f + c2[1] * (1 - f),
            c1[2] * f + c2[2] * (1 - f)
        ];
    }

    function brightenColor(rgb, factor) {
        return [
            rgb[0] * factor,
            rgb[1] * factor,
            rgb[2] * factor
        ];
    }

    /* ── Find slider def by id ────────────────────────────────────── */
    function findSlider(id) {
        for (var i = 0; i < ALL_SLIDERS.length; i++) {
            if (ALL_SLIDERS[i].id === id) return ALL_SLIDERS[i];
        }
        return null;
    }

    function getById(state, id) {
        var s = findSlider(id);
        return s ? getVal(state, s) : 0;
    }

    /* ── Apply tuning values ───────────────────────────────────────── */
    function applyTuning(state) {
        var root = document.documentElement;

        // --- UI filters (tree pane, navbar, status bar) ---
        var brightness = getById(state, 'brightness');
        var contrast   = getById(state, 'contrast');
        var saturation = getById(state, 'saturation');
        var textBoost  = getById(state, 'textBoost');
        var accentBright = getById(state, 'accentBright');

        var uiParts = [];
        if (brightness !== 1.0) uiParts.push('brightness(' + brightness + ')');
        if (contrast !== 1.0)   uiParts.push('contrast(' + contrast + ')');
        if (saturation !== 1.0) uiParts.push('saturate(' + saturation + ')');

        var uiFilter = uiParts.length ? uiParts.join(' ') : '';

        var treePane = document.getElementById('treePane');
        var statusBar = document.getElementById('statusBar');
        var navbar = document.querySelector('.navbar');
        if (treePane) treePane.style.filter = uiFilter;
        if (statusBar) statusBar.style.filter = uiFilter;
        if (navbar) navbar.style.filter = uiFilter;

        // Text readability: recalculate --text-muted
        var baseMix = 68;
        var effectiveMix = Math.min(100, baseMix + textBoost);
        root.style.setProperty('--text-muted',
            'color-mix(in srgb, var(--c-text) ' + effectiveMix + '%, var(--c-bg))');

        // Accent brightness
        if (accentBright !== 1.0) {
            var accentRgb = parseColor(getCssVar('--c-accent'));
            var brightened = brightenColor(accentRgb, accentBright);
            root.style.setProperty('--accent', rgbToHex(brightened[0], brightened[1], brightened[2]));
        } else {
            root.style.setProperty('--accent', 'var(--c-accent)');
        }

        // --- Document filters (PDF viewer canvases) ---
        var docBr  = getById(state, 'docBrightness');
        var docCo  = getById(state, 'docContrast');
        var docSa  = getById(state, 'docSaturation');
        var docGr  = getById(state, 'docGrayscale');
        var docIn  = getById(state, 'docInvert');
        var docSe  = getById(state, 'docSepia');
        var docHu  = getById(state, 'docHueRotate');

        var docParts = [];
        if (docBr !== 1.0)  docParts.push('brightness(' + docBr + ')');
        if (docCo !== 1.0)  docParts.push('contrast(' + docCo + ')');
        if (docSa !== 1.0)  docParts.push('saturate(' + docSa + ')');
        if (docGr > 0)      docParts.push('grayscale(' + docGr + '%)');
        if (docIn > 0)      docParts.push('invert(' + docIn + '%)');
        if (docSe > 0)      docParts.push('sepia(' + docSe + '%)');
        if (docHu > 0)      docParts.push('hue-rotate(' + docHu + 'deg)');

        var docFilter = docParts.length ? docParts.join(' ') : '';

        var pdfViewer = document.getElementById('pdfViewer');
        var pdfStaging = document.getElementById('pdfViewerStaging');
        if (pdfViewer) pdfViewer.style.filter = docFilter;
        if (pdfStaging) pdfStaging.style.filter = docFilter;
    }

    /* ── Format display value ──────────────────────────────────────── */
    function formatVal(slider, val) {
        if (slider.unit === '%') return val + '%';
        if (slider.unit === 'deg') return val + '\u00B0';
        return (val * 100).toFixed(0) + '%';
    }

    /* ── Build a slider row ────────────────────────────────────────── */
    function buildSliderRow(slider, state) {
        var val = getVal(state, slider);

        var row = document.createElement('div');
        row.className = 'dt-slider-row';

        var labelRow = document.createElement('div');
        labelRow.className = 'dt-slider-label-row';
        labelRow.innerHTML =
            '<span class="dt-slider-label" title="' + slider.tooltip + '">' +
            '<i class="fas ' + slider.icon + ' me-1"></i>' + slider.label + '</span>' +
            '<span class="dt-slider-val" id="dtVal_' + slider.id + '">' +
            formatVal(slider, val) + '</span>';
        row.appendChild(labelRow);

        var inputRow = document.createElement('div');
        inputRow.className = 'dt-slider-input-row';

        var range = document.createElement('input');
        range.type = 'range';
        range.className = 'dt-slider';
        range.id = 'dtSlider_' + slider.id;
        range.min = slider.min;
        range.max = slider.max;
        range.step = slider.step;
        range.value = val;
        range.title = slider.tooltip;
        inputRow.appendChild(range);

        var resetBtn = document.createElement('button');
        resetBtn.type = 'button';
        resetBtn.className = 'dt-slider-reset';
        resetBtn.title = 'Reset to default';
        resetBtn.innerHTML = '<i class="fas fa-rotate-left"></i>';
        resetBtn.dataset.sliderId = slider.id;
        inputRow.appendChild(resetBtn);

        row.appendChild(inputRow);
        return row;
    }

    /* ── Build panel ───────────────────────────────────────────────── */
    function buildPanel() {
        var state = loadState();

        var panel = document.createElement('div');
        panel.id = 'displayTuningPanel';

        // Header
        var hdr = document.createElement('div');
        hdr.className = 'dt-panel-header';
        hdr.innerHTML =
            '<span><i class="fas fa-display me-2"></i>Display Tuning</span>' +
            '<button type="button" class="dt-panel-close" id="displayTuningClose" title="Close"' +
            ' aria-label="Close"><i class="fas fa-times"></i></button>';
        panel.appendChild(hdr);

        var scrollBody = document.createElement('div');
        scrollBody.className = 'dt-panel-scroll';

        GROUPS.forEach(function (group) {
            var section = document.createElement('div');
            section.className = 'dt-section';

            var sectionHdr = document.createElement('div');
            sectionHdr.className = 'dt-section-header';
            sectionHdr.innerHTML =
                '<i class="fas ' + group.icon + ' me-1"></i>' + group.label;
            section.appendChild(sectionHdr);

            var body = document.createElement('div');
            body.className = 'dt-panel-body';

            group.sliders.forEach(function (slider) {
                body.appendChild(buildSliderRow(slider, state));
            });

            section.appendChild(body);
            scrollBody.appendChild(section);
        });

        panel.appendChild(scrollBody);

        // Footer: reset all button
        var footer = document.createElement('div');
        footer.className = 'dt-panel-footer';
        footer.innerHTML =
            '<button type="button" class="dt-reset-all" id="displayTuningResetAll">' +
            '<i class="fas fa-rotate-left me-1"></i>Reset All</button>';
        panel.appendChild(footer);

        return panel;
    }

    /* ── Wire events ───────────────────────────────────────────────── */
    function wireEvents(panel) {
        var state = loadState();

        ALL_SLIDERS.forEach(function (slider) {
            var range = panel.querySelector('#dtSlider_' + slider.id);
            var valEl = panel.querySelector('#dtVal_' + slider.id);

            range.addEventListener('input', function () {
                var v = parseFloat(range.value);
                state[slider.id] = v;
                valEl.textContent = formatVal(slider, v);
                saveState(state);
                applyTuning(state);
            });
        });

        // Individual reset buttons
        panel.querySelectorAll('.dt-slider-reset').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var sid = btn.dataset.sliderId;
                var slider = findSlider(sid);
                if (!slider) return;
                var range = panel.querySelector('#dtSlider_' + sid);
                var valEl = panel.querySelector('#dtVal_' + sid);
                range.value = slider.defaultVal;
                state[sid] = slider.defaultVal;
                valEl.textContent = formatVal(slider, slider.defaultVal);
                saveState(state);
                applyTuning(state);
            });
        });

        // Reset all
        panel.querySelector('#displayTuningResetAll').addEventListener('click', function () {
            state = {};
            saveState(state);
            ALL_SLIDERS.forEach(function (slider) {
                var range = panel.querySelector('#dtSlider_' + slider.id);
                var valEl = panel.querySelector('#dtVal_' + slider.id);
                range.value = slider.defaultVal;
                valEl.textContent = formatVal(slider, slider.defaultVal);
            });
            applyTuning(state);
        });

        // Close button
        panel.querySelector('#displayTuningClose').addEventListener('click', function () {
            panel.classList.remove('open');
        });
    }

    /* ── Close sibling panels ──────────────────────────────────────── */
    function closeSiblingPanels() {
        var theme = document.getElementById('themePickerPanel');
        var design = document.getElementById('designPickerPanel');
        if (theme) theme.classList.remove('open');
        if (design) design.classList.remove('open');
    }

    /* ── Toggle ────────────────────────────────────────────────────── */
    function togglePanel() {
        var panel = document.getElementById('displayTuningPanel');
        if (!panel) return;
        var opening = !panel.classList.contains('open');
        if (opening) closeSiblingPanels();
        panel.classList.toggle('open');
    }

    /* ── Close on outside click ────────────────────────────────────── */
    function onDocClick(e) {
        var panel = document.getElementById('displayTuningPanel');
        var btn = document.getElementById('displayTuningStatusBtn');
        if (!panel || !panel.classList.contains('open')) return;
        if (panel.contains(e.target) || (btn && btn.contains(e.target))) return;
        panel.classList.remove('open');
    }

    /* ── Init ──────────────────────────────────────────────────────── */
    function init() {
        var state = loadState();
        applyTuning(state);

        var panel = buildPanel();
        document.body.appendChild(panel);
        wireEvents(panel);

        var statusBtn = document.getElementById('displayTuningStatusBtn');
        if (statusBtn) {
            statusBtn.addEventListener('click', togglePanel);
        }

        document.addEventListener('click', onDocClick, true);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // Early-apply before DOM ready
    (function earlyApply() {
        try {
            var raw = localStorage.getItem(STORAGE_KEY);
            if (!raw) return;
            var state = JSON.parse(raw);
            var textBoost = state.textBoost || 0;
            if (textBoost > 0) {
                var effectiveMix = Math.min(100, 68 + textBoost);
                document.documentElement.style.setProperty('--text-muted',
                    'color-mix(in srgb, var(--c-text) ' + effectiveMix + '%, var(--c-bg))');
            }
        } catch (e) { /* ignore */ }
    })();
})();

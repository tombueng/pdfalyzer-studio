/**
 * PDFalyzer Studio – Signing Wizard: multi-step modal for digitally signing PDF signature fields.
 * Steps: 1) Signature type (approval/certification), 2) Visual representation (approval only), 3) Certificate, 4) Options, 5) Confirm.
 */
PDFalyzer.Signing = (function ($, P) {
    'use strict';

    function getSteps() {
        if (_wizardState.signMode === 'certification') return ['type', 'certificate', 'options', 'confirm'];
        return ['type', 'visual', 'certificate', 'options', 'confirm'];
    }
    var SIGNING_FONTS = [
        { id: 'DancingScript',    css: 'Dancing Script',    label: 'Dancing Script' },
        { id: 'Caveat',           css: 'Caveat',            label: 'Caveat' },
        { id: 'Sacramento',       css: 'Sacramento',        label: 'Sacramento' },
        { id: 'GreatVibes',       css: 'Great Vibes',       label: 'Great Vibes' },
        { id: 'Kalam',            css: 'Kalam',             label: 'Kalam' },
        { id: 'IndieFlower',      css: 'Indie Flower',      label: 'Indie Flower' },
        { id: 'Satisfy',          css: 'Satisfy',           label: 'Satisfy' },
        { id: 'Allura',           css: 'Allura',            label: 'Allura' },
        { id: 'AlexBrush',        css: 'Alex Brush',        label: 'Alex Brush' },
        { id: 'HomemadeApple',    css: 'Homemade Apple',    label: 'Homemade Apple' },
        { id: 'Pacifico',         css: 'Pacifico',          label: 'Pacifico' },
        { id: 'PermanentMarker',  css: 'Permanent Marker',  label: 'Permanent Marker' }
    ];

    var _modal = null;
    var _bsModal = null;
    var _currentStep = 0;
    var _fieldName = '';
    var _wizardState = {};

    // ── Public entry point ──────────────────────────────────────────────

    function openWizard(fieldName) {
        _fieldName = fieldName;
        _currentStep = 0;
        _wizardState = {
            visualMode: 'text',
            displayName: '',
            fontName: 'DancingScript',
            imageDataBase64: null,
            drawnImageBase64: null,
            sessionKeyId: null,
            signMode: 'approval',
            docMdpLevel: 2,
            padesProfile: 'B-B',
            excludedFields: [],
            reason: '',
            location: '',
            contactInfo: '',
            drawPenWidth: 2.0,
            drawSmoothing: 0.5,
            drawSmoothAlgo: 'chaikin',
            biometricEnabled: true,
            bioFormat: 'json-zip',
            biometricData: null,
            tsaServerId: null,
            tsaUrl: null,
            tsaServerName: null,
            // CSC remote signing state
            cscProviderId: null,
            cscProviderName: null,
            cscCredentialId: null,
            cscConnected: false,
            cscCertMode: 'local' // 'local' or 'csc'
        };

        ensureModal();
        renderWizard();
        _bsModal.show();
        refreshKeysInBackground();
    }

    // ── Modal skeleton ──────────────────────────────────────────────────

    function ensureModal() {
        if (_modal) return;
        var $m = $('#signingWizardModal');
        if (!$m.length) return;
        _modal = $m;
        _bsModal = new bootstrap.Modal($m[0], { backdrop: 'static', keyboard: false });

        $m.on('hidden.bs.modal', function () {
            if (P.SigningCanvas) P.SigningCanvas.destroy();
        });
    }

    function renderWizard() {
        if (!_modal) return;
        var html = '<div class="modal-content">';
        html += '<div class="modal-header"><h5 class="modal-title"><i class="fas fa-file-signature me-2"></i>Sign Field: ' + P.Utils.escapeHtml(_fieldName) + '</h5>';
        html += '<button type="button" class="btn-close" data-bs-dismiss="modal"></button></div>';
        html += buildStepIndicator();
        html += '<div class="modal-body" style="padding:0;">';
        html += buildTypePanel();
        html += buildVisualPanel();
        html += buildCertificatePanel();
        html += buildOptionsPanel();
        html += buildConfirmPanel();
        html += '</div>';
        html += buildFooter();
        html += '</div>';

        _modal.find('.modal-dialog').html(html);
        activateStep(_currentStep);
        bindEvents();
    }

    var STEP_META = {
        type:        { label: 'Type',        icon: 'fa-stamp' },
        visual:      { label: 'Visual',      icon: 'fa-pen-fancy' },
        certificate: { label: 'Certificate', icon: 'fa-certificate' },
        options:     { label: 'Options',     icon: 'fa-sliders' },
        confirm:     { label: 'Confirm',     icon: 'fa-check-double' }
    };

    function buildStepIndicator() {
        var steps = getSteps();
        var html = '<div class="signing-steps">';
        for (var i = 0; i < steps.length; i++) {
            var meta = STEP_META[steps[i]];
            if (i > 0) html += '<div class="signing-step-connector' + (i <= _currentStep ? ' done' : '') + '"></div>';
            var cls = i < _currentStep ? 'done' : (i === _currentStep ? 'active' : '');
            html += '<div class="signing-step ' + cls + '" data-step="' + i + '">';
            html += '<span class="signing-step-num">' + (i < _currentStep ? '<i class="fas fa-check"></i>' : (i + 1)) + '</span>';
            html += '<span class="signing-step-label d-none d-sm-inline">' + meta.label + '</span>';
            html += '</div>';
        }
        html += '</div>';
        return html;
    }

    // ── Step 1: Type ────────────────────────────────────────────────────

    function buildTypePanel() {
        var html = '<div class="signing-panel" data-panel="type">';

        html += '<div class="signing-option-group"><label>Signature Type</label>';
        html += '<div class="signing-option-radio">';
        html += '<label><input type="radio" name="sigSignMode" value="approval"' + (_wizardState.signMode === 'approval' ? ' checked' : '') + '> Approval Signature';
        html += '<div class="option-desc">Confirms agreement or review. Multiple approval signatures can coexist. Can optionally lock specific fields via a Lock dictionary. Includes a visual representation on the page.</div></label>';
        html += '<label><input type="radio" name="sigSignMode" value="certification"' + (_wizardState.signMode === 'certification' ? ' checked' : '') + '> Certification Signature';
        html += '<div class="option-desc">Certifies the document as the author. Only one per PDF. Sets document-wide permissions for allowed modifications (DocMDP). No visible appearance on the page.</div></label>';
        html += '</div></div>';

        // DocMDP (only for certification)
        html += '<div class="signing-option-group" id="sigDocMdpGroup"' + (_wizardState.signMode !== 'certification' ? ' style="display:none;"' : '') + '>';
        html += '<label>Allowed Modifications (DocMDP)</label>';
        html += '<div class="signing-option-radio">';
        html += '<label><input type="radio" name="sigDocMdp" value="1"' + (_wizardState.docMdpLevel === 1 ? ' checked' : '') + '> No changes allowed';
        html += '<div class="option-desc">The document is locked. No modifications of any kind are permitted.</div></label>';
        html += '<label><input type="radio" name="sigDocMdp" value="2"' + (_wizardState.docMdpLevel === 2 ? ' checked' : '') + '> Form filling and signing allowed';
        html += '<div class="option-desc">Users can fill form fields and add approval signatures. Most common for fillable forms.</div></label>';
        html += '<label><input type="radio" name="sigDocMdp" value="3"' + (_wizardState.docMdpLevel === 3 ? ' checked' : '') + '> Form filling, signing, and annotations allowed';
        html += '<div class="option-desc">Like level 2, plus annotations (comments, stamps) can be added.</div></label>';
        html += '</div></div>';

        html += '</div>';
        return html;
    }

    // ── Step 2: Visual ──────────────────────────────────────────────────

    function buildVisualPanel() {
        var html = '<div class="signing-panel" data-panel="visual">';

        // Preview area (always visible above tabs)
        html += '<div class="signing-font-preview" id="sigFontPreview" style="font-family:\'' + (_wizardState.fontName || 'Dancing Script') + '\'">' + P.Utils.escapeHtml(_wizardState.displayName || 'Your Name') + '</div>';

        html += '<div class="signing-visual-tabs">';
        html += '<div class="signing-visual-tab' + (_wizardState.visualMode === 'text' ? ' active' : '') + '" data-vmode="text"><i class="fas fa-font"></i>Name &amp; Font</div>';
        html += '<div class="signing-visual-tab' + (_wizardState.visualMode === 'image' ? ' active' : '') + '" data-vmode="image"><i class="fas fa-image"></i>Image</div>';
        html += '<div class="signing-visual-tab' + (_wizardState.visualMode === 'draw' ? ' active' : '') + '" data-vmode="draw"><i class="fas fa-pen"></i>Draw</div>';
        html += '<div class="signing-visual-tab' + (_wizardState.visualMode === 'invisible' ? ' active' : '') + '" data-vmode="invisible"><i class="fas fa-eye-slash"></i>Invisible</div>';
        html += '</div>';

        // Scrollable area for mode content + profiles
        html += '<div class="signing-visual-scroll">';

        // Text mode
        html += '<div class="signing-visual-content' + (_wizardState.visualMode === 'text' ? ' active' : '') + '" data-vcontent="text">';
        html += '<div class="mb-2"><label class="form-label">Display Name</label>';
        html += '<input type="text" class="form-control form-control-sm" id="sigDisplayName" placeholder="e.g. John Doe" value="' + P.Utils.escapeHtml(_wizardState.displayName || '') + '"></div>';
        html += '<div class="mb-2"><label class="form-label">Font</label>';
        html += '<div class="signing-font-grid" id="sigFontGrid">';
        var fonts = SIGNING_FONTS;
        for (var f = 0; f < fonts.length; f++) {
            html += '<div class="signing-font-option' + (fonts[f].id === _wizardState.fontName ? ' active' : '') + '" data-font="' + fonts[f].id + '" style="font-family:\'' + fonts[f].css + '\'">' + fonts[f].label + '</div>';
        }
        html += '</div></div>';
        html += '</div>';

        // Image mode
        html += '<div class="signing-visual-content' + (_wizardState.visualMode === 'image' ? ' active' : '') + '" data-vcontent="image">';
        html += '<div class="signing-upload-zone" id="sigImageDropZone"><i class="fas fa-cloud-upload-alt"></i>Drop image here or click to browse<input type="file" id="sigImageInput" accept="image/*" style="display:none;"></div>';
        if (_wizardState.imageDataBase64) {
            html += '<img class="signing-image-preview" id="sigImagePreview" src="' + _wizardState.imageDataBase64 + '">';
        } else {
            html += '<img class="signing-image-preview" id="sigImagePreview" style="display:none;">';
        }
        html += '</div>';

        // Draw mode
        html += '<div class="signing-visual-content' + (_wizardState.visualMode === 'draw' ? ' active' : '') + '" data-vcontent="draw">';
        html += '<div class="signing-canvas-container"><canvas id="sigDrawCanvas" width="600" height="180"></canvas>';
        html += '<div class="signing-canvas-toolbar">';
        html += '<button class="btn btn-outline-secondary" id="sigDrawUndo"><i class="fas fa-undo me-1"></i>Undo</button>';
        html += '<button class="btn btn-outline-secondary" id="sigDrawClear"><i class="fas fa-trash me-1"></i>Clear</button>';
        html += '<span class="signing-tb-sep"></span>';
        html += '<label class="signing-tb-label" title="Base pen width">Width</label>';
        html += '<input type="range" class="signing-tb-slider" id="sigPenWidth" min="0.5" max="8" step="0.5" value="' + (_wizardState.drawPenWidth || 2.0) + '">';
        html += '<span class="signing-tb-val" id="sigPenWidthVal">' + (_wizardState.drawPenWidth || 2.0) + '</span>';
        html += '<span class="signing-tb-sep"></span>';
        var algoVal = _wizardState.drawSmoothAlgo || 'chaikin';
        html += '<label class="signing-tb-label" title="Smoothing algorithm">Algo</label>';
        html += '<select class="signing-tb-select" id="sigSmoothAlgo">';
        html += '<option value="none"' + (algoVal === 'none' ? ' selected' : '') + '>None</option>';
        html += '<option value="bezier"' + (algoVal === 'bezier' ? ' selected' : '') + '>Bezier</option>';
        html += '<option value="chaikin"' + (algoVal === 'chaikin' ? ' selected' : '') + '>Chaikin</option>';
        html += '<option value="gaussian"' + (algoVal === 'gaussian' ? ' selected' : '') + '>Gaussian</option>';
        html += '<option value="catmull"' + (algoVal === 'catmull' ? ' selected' : '') + '>Catmull-Rom</option>';
        html += '<option value="bspline"' + (algoVal === 'bspline' ? ' selected' : '') + '>B-Spline</option>';
        html += '</select>';
        html += '<span class="signing-tb-sep"></span>';
        html += '<label class="signing-tb-label" title="Smoothing intensity / iterations">Level</label>';
        html += '<input type="range" class="signing-tb-slider" id="sigSmoothing" min="0" max="50" step="0.1" value="' + (_wizardState.drawSmoothing != null ? _wizardState.drawSmoothing : 0.5) + '">';
        html += '<span class="signing-tb-val" id="sigSmoothVal">' + (_wizardState.drawSmoothing != null ? _wizardState.drawSmoothing : 0.5) + '</span>';
        html += '</div></div>';
        // Biometric data options
        html += '<div class="signing-bio-options mt-2">';
        html += '<div class="d-flex align-items-center gap-2 flex-wrap">';
        html += '<label class="form-check form-check-inline mb-0"><input class="form-check-input" type="checkbox" id="sigBiometric"' + (_wizardState.biometricEnabled !== false ? ' checked' : '') + '>';
        html += '<span class="form-check-label">Record biometric data</span></label>';
        html += '<select class="form-select form-select-sm" id="sigBioFormat" style="width:auto;min-width:160px;"' + (_wizardState.biometricEnabled === false ? ' disabled' : '') + '>';
        html += '<option value="json"' + (_wizardState.bioFormat === 'json' ? ' selected' : '') + '>JSON (uncompressed)</option>';
        html += '<option value="json-zip"' + (_wizardState.bioFormat === 'json-zip' || !_wizardState.bioFormat ? ' selected' : '') + '>JSON (deflate-compressed)</option>';
        html += '<option value="binary"' + (_wizardState.bioFormat === 'binary' ? ' selected' : '') + '>Binary (compact)</option>';
        html += '</select>';
        html += '<span class="signing-bio-info"><i class="fas fa-info-circle"></i> Captures x, y, pressure, tilt, and timing per point</span>';
        html += '<button class="btn btn-outline-accent btn-sm ms-auto" id="sigBioInspect" title="Inspect biometric data"><i class="fas fa-microscope me-1"></i>Inspect</button>';
        html += '</div></div>';
        html += '</div>';

        // Invisible mode
        html += '<div class="signing-visual-content' + (_wizardState.visualMode === 'invisible' ? ' active' : '') + '" data-vcontent="invisible">';
        html += '<div class="text-muted text-center mt-3"><i class="fas fa-eye-slash fa-2x mb-2"></i><br>';
        html += 'The signature will be applied without any visible representation on the page.<br>';
        html += '<small>The signature field will still be listed in PDF reader signature panels.</small></div>';
        html += '</div>';

        // Stored profiles
        html += '<hr class="my-2"><h6><i class="fas fa-bookmark me-1"></i>Saved Profiles</h6>';
        html += '<div class="signing-profiles-list" id="sigProfilesList"><div class="text-muted text-center" style="font-size:11px;">Loading...</div></div>';
        html += '<div class="d-flex gap-2 mt-1 align-items-center">';
        html += '<input type="text" class="form-control form-control-sm" id="sigProfileName" placeholder="Profile name" style="max-width:180px;" value="' + P.Utils.escapeHtml(_wizardState.displayName || '') + '">';
        html += '<button class="btn btn-outline-accent btn-sm" id="sigSaveProfile"' + (!_wizardState.displayName ? ' disabled' : '') + '><i class="fas fa-save me-1"></i>Save</button>';
        html += '</div>';

        html += '</div>'; // close signing-visual-scroll
        html += '</div>'; // close signing-panel
        return html;
    }

    // ── Step 2: Certificate ─────────────────────────────────────────────

    function buildCertificatePanel() {
        var html = '<div class="signing-panel" data-panel="certificate">';

        html += '<h6><i class="fas fa-upload me-1"></i>Upload Key Material</h6>';
        html += '<div class="signing-upload-zone" id="sigCertDropZone"><i class="fas fa-key"></i>Drop certificate/key file (P12, PFX, PEM, JKS, DER)<input type="file" id="sigCertInput" accept=".p12,.pfx,.pem,.key,.crt,.cer,.der,.jks" style="display:none;"></div>';
        html += '<div id="sigCertPasswordRow" style="display:none;" class="mb-2"><label class="form-label">Password</label>';
        html += '<div class="input-group input-group-sm"><input type="password" class="form-control form-control-sm" id="sigCertPassword" placeholder="Key/keystore password"><button class="btn btn-accent btn-sm" id="sigCertUploadBtn">Upload</button></div></div>';

        html += '<h6 class="mt-3"><i class="fas fa-key me-1"></i>Available Certificates</h6>';
        html += '<div class="signing-keys-list" id="sigKeysList"><div class="text-muted text-center" style="font-size:11px;">No certificates loaded</div></div>';

        html += '<h6 class="mt-3"><i class="fas fa-wand-magic-sparkles me-1"></i>Generate Self-Signed Certificate</h6>';
        html += '<div class="signing-self-signed-form">';
        // Subject DN
        html += '<div class="row g-2">';
        html += '<div class="col-5"><label class="form-label">Common Name *</label><input type="text" class="form-control form-control-sm" id="sigSsCn" placeholder="Your Name"></div>';
        html += '<div class="col-4"><label class="form-label">Organization</label><input type="text" class="form-control form-control-sm" id="sigSsOrg" placeholder="Company"></div>';
        html += '<div class="col-3"><label class="form-label">Country</label><input type="text" class="form-control form-control-sm" id="sigSsCountry" placeholder="CH" maxlength="2"></div>';
        html += '</div>';
        // Algorithm selection
        html += '<div class="row g-2 mt-1">';
        html += '<div class="col-4"><label class="form-label">Key Algorithm</label>';
        html += '<select class="form-select form-select-sm" id="sigSsAlgo">';
        html += '<option value="RSA">RSA</option>';
        html += '<option value="EC">ECDSA</option>';
        html += '<option value="ED25519">Ed25519</option>';
        html += '<option value="ED448">Ed448</option>';
        html += '</select></div>';
        html += '<div class="col-4"><label class="form-label">Key Size</label>';
        html += '<select class="form-select form-select-sm" id="sigSsKeySize">';
        html += '<option value="2048">2048 bit</option>';
        html += '<option value="3072">3072 bit</option>';
        html += '<option value="4096" selected>4096 bit</option>';
        html += '<option value="8192">8192 bit</option>';
        html += '</select></div>';
        html += '<div class="col-4"><label class="form-label">Signature Hash</label>';
        html += '<select class="form-select form-select-sm" id="sigSsSigAlgo">';
        html += '<option value="">Auto (match key size)</option>';
        html += '<option value="SHA256withRSA">SHA-256</option>';
        html += '<option value="SHA384withRSA">SHA-384</option>';
        html += '<option value="SHA512withRSA">SHA-512</option>';
        html += '<option value="SHA256withRSAandMGF1">SHA-256 + PSS</option>';
        html += '<option value="SHA384withRSAandMGF1">SHA-384 + PSS</option>';
        html += '<option value="SHA512withRSAandMGF1">SHA-512 + PSS</option>';
        html += '</select></div>';
        html += '</div>';
        html += '<div class="row g-2 mt-1">';
        html += '<div class="col-4"><label class="form-label">Validity (days)</label><input type="number" class="form-control form-control-sm" id="sigSsDays" value="365" min="1" max="3650"></div>';
        html += '<div class="col-8 d-flex align-items-end"><button class="btn btn-outline-accent btn-sm" id="sigGenerateSs"><i class="fas fa-wand-magic-sparkles me-1"></i>Generate Certificate</button></div>';
        html += '</div>';
        // Recommendation hint area
        html += '<div class="sig-algo-hint" id="sigAlgoHint"></div>';
        // Reference table (collapsible)
        html += '<details class="sig-algo-ref mt-2"><summary><i class="fas fa-book me-1"></i>Algorithm reference &amp; recommendations</summary>';
        html += '<div class="sig-algo-ref-body">';
        html += '<table class="sig-algo-ref-table"><thead><tr><th>Algorithm</th><th>Key Size</th><th>Security</th><th>Speed</th><th>PDF Compat.</th><th>Recommendations</th></tr></thead><tbody>';
        html += '<tr><td>RSA + PKCS#1 v1.5</td><td>2048</td><td class="sig-sec-ok">112 bit</td><td class="sig-spd-slow">Slow</td><td class="sig-compat-best">Universal</td><td>NIST min. until 2030. BSI: acceptable until end of 2025</td></tr>';
        html += '<tr><td>RSA + PKCS#1 v1.5</td><td>3072</td><td class="sig-sec-good">128 bit</td><td class="sig-spd-slow">Slow</td><td class="sig-compat-best">Universal</td><td>NIST recommended. BSI: acceptable until end of 2029</td></tr>';
        html += '<tr class="sig-ref-highlight"><td>RSA + PKCS#1 v1.5</td><td>4096</td><td class="sig-sec-strong">~140 bit</td><td class="sig-spd-med">Medium</td><td class="sig-compat-best">Universal</td><td>BSI recommended for long-term. Very wide support</td></tr>';
        html += '<tr><td>RSA-PSS</td><td>3072+</td><td class="sig-sec-good">128+ bit</td><td class="sig-spd-med">Medium</td><td class="sig-compat-good">Good</td><td>NIST/BSI preferred over PKCS#1. Requires PDF 2.0 aware readers</td></tr>';
        html += '<tr><td>ECDSA (P-256)</td><td>256</td><td class="sig-sec-good">128 bit</td><td class="sig-spd-fast">Fast</td><td class="sig-compat-good">Good</td><td>NIST Suite B. BSI recommended. Small signatures</td></tr>';
        html += '<tr class="sig-ref-highlight"><td>ECDSA (P-384)</td><td>384</td><td class="sig-sec-strong">192 bit</td><td class="sig-spd-fast">Fast</td><td class="sig-compat-good">Good</td><td>NIST TOP SECRET level (CNSA 1.0). Excellent balance</td></tr>';
        html += '<tr><td>ECDSA (P-521)</td><td>521</td><td class="sig-sec-strong">256 bit</td><td class="sig-spd-fast">Fast</td><td class="sig-compat-ok">Limited</td><td>Maximum classical security. Less common in PDF</td></tr>';
        html += '<tr><td>Ed25519</td><td>255</td><td class="sig-sec-good">128 bit</td><td class="sig-spd-best">Fastest</td><td class="sig-compat-low">Minimal</td><td>Modern, constant-time. Not yet standard in PDF/PAdES</td></tr>';
        html += '<tr><td>Ed448</td><td>448</td><td class="sig-sec-strong">224 bit</td><td class="sig-spd-best">Fastest</td><td class="sig-compat-low">Minimal</td><td>Strongest EdDSA. Experimental in PDF context</td></tr>';
        html += '</tbody></table>';
        html += '<div class="sig-algo-ref-notes">';
        html += '<strong>Sources:</strong> BSI TR-02102-1 (2024), NIST SP 800-57 Part 1 Rev. 5, CNSA 1.0/2.0 (NSA)';
        html += '<br><strong>Note:</strong> "Security" column shows equivalent symmetric key strength. 128-bit is considered secure through 2030+.';
        html += '<br><strong>PDF compatibility:</strong> RSA has universal reader support. ECDSA works in modern readers (Acrobat 2017+). EdDSA has no standard PDF support yet.';
        html += '<br><strong>RSA-PSS</strong> (RSASSA-PSS) is the provably secure variant of RSA signatures, preferred by BSI/NIST over legacy PKCS#1 v1.5.';
        html += '</div></div></details>';
        html += '</div>';

        // ── CSC Remote Signing section ──────────────────────────────────
        html += '<h6 class="mt-3"><i class="fas fa-cloud me-1"></i>Remote Signing (CSC)</h6>';
        html += '<div class="csc-section" id="sigCscSection">';
        html += '<div class="signing-info-tip mb-2"><i class="fas fa-info-circle me-1"></i>Sign with a remote <strong>Cloud Signature Consortium (CSC)</strong> provider. Your private key never leaves the provider\'s HSM.</div>';

        // Provider selector
        html += '<div class="csc-provider-row">';
        html += '<label class="form-label">Provider</label>';
        html += '<div class="d-flex gap-2 align-items-center">';
        html += '<select class="form-select form-select-sm" id="sigCscProvider" style="flex:1;">';
        html += '<option value="">Loading providers...</option>';
        html += '</select>';
        html += '<button class="btn btn-outline-secondary btn-sm" id="sigCscAddProvider" title="Add custom provider"><i class="fas fa-plus"></i></button>';
        html += '</div>';
        html += '</div>';

        // Provider info card
        html += '<div class="csc-provider-info" id="sigCscProviderInfo"></div>';

        // Credentials form (hidden until provider selected)
        html += '<div class="csc-connect-form" id="sigCscConnectForm" style="display:none;">';
        html += '<div class="row g-2 mt-1">';
        html += '<div class="col-6"><input type="text" class="form-control form-control-sm" id="sigCscUsername" placeholder="Username / email"></div>';
        html += '<div class="col-6"><input type="password" class="form-control form-control-sm" id="sigCscPassword" placeholder="Password / API key"></div>';
        html += '</div>';
        html += '<div class="mt-2"><button class="btn btn-accent btn-sm" id="sigCscConnectBtn"><i class="fas fa-plug me-1"></i>Connect</button></div>';
        html += '</div>';

        // Connection status
        html += '<div class="csc-status" id="sigCscStatus"></div>';

        // Credentials list (populated after connect)
        html += '<div class="csc-credentials" id="sigCscCredentials" style="display:none;">';
        html += '<label class="form-label"><i class="fas fa-id-card me-1"></i>Available Credentials</label>';
        html += '<div class="csc-credentials-list" id="sigCscCredentialsList"></div>';
        html += '</div>';

        // OTP input (for SCAL2 credentials)
        html += '<div class="csc-otp-section" id="sigCscOtpSection" style="display:none;">';
        html += '<div class="d-flex gap-2 align-items-end mt-2">';
        html += '<div style="flex:1;"><label class="form-label">OTP / Authorization Code</label>';
        html += '<input type="text" class="form-control form-control-sm" id="sigCscOtp" placeholder="Enter OTP from your device"></div>';
        html += '<button class="btn btn-outline-secondary btn-sm" id="sigCscSendOtp" title="Request OTP via SMS/push"><i class="fas fa-paper-plane me-1"></i>Send OTP</button>';
        html += '</div>';
        html += '</div>';

        html += '</div>'; // close csc-section

        html += '<div class="signing-info-tip mt-2"><i class="fas fa-info-circle me-1"></i>Private keys are held <strong>server-side in memory only</strong> for the duration of the session (local certificates). CSC remote keys never leave the provider\'s HSM.</div>';

        html += '</div>';
        return html;
    }

    // ── Step 3: Options ─────────────────────────────────────────────────

    function buildOptionsPanel() {
        var html = '<div class="signing-panel" data-panel="options">';

        // PAdES profile
        html += '<div class="signing-option-group"><label>PAdES Profile</label>';
        html += '<select class="form-select form-select-sm" id="sigPadesProfile" style="max-width:200px;">';
        html += '<option value="B-B"' + (_wizardState.padesProfile === 'B-B' ? ' selected' : '') + '>PAdES Baseline B-B</option>';
        html += '<option value="B-T"' + (_wizardState.padesProfile === 'B-T' ? ' selected' : '') + '>PAdES Baseline B-T (with timestamp)</option>';
        html += '</select>';
        html += '<div class="signing-info-tip mt-1"><i class="fas fa-info-circle me-1"></i><strong>B-B</strong> includes the signing certificate. <strong>B-T</strong> adds a trusted timestamp from a TSA server (RFC 3161).</div>';
        html += '</div>';

        // TSA Server selection (visible when B-T is selected)
        var showTsa = _wizardState.padesProfile === 'B-T';
        html += '<div class="signing-option-group" id="sigTsaGroup"' + (showTsa ? '' : ' style="display:none;"') + '>';
        html += '<label><i class="fas fa-clock me-1"></i>Time Stamp Authority (TSA)</label>';
        html += '<div class="tsa-controls">';
        html += '<select class="form-select form-select-sm" id="sigTsaServer">';
        html += '<option value="">Loading TSA servers...</option>';
        html += '</select>';
        html += '<button class="btn btn-outline-secondary btn-sm" id="sigTsaRefresh" title="Re-probe all TSA servers"><i class="fas fa-sync-alt"></i></button>';
        html += '</div>';
        html += '<div class="tsa-server-info" id="sigTsaInfo"></div>';
        html += '</div>';

        // Reason / location / contact
        html += '<div class="signing-option-group"><label>Metadata (optional)</label>';
        html += '<div class="row g-2">';
        html += '<div class="col-12"><input type="text" class="form-control form-control-sm" id="sigReason" placeholder="Reason for signing" value="' + P.Utils.escapeHtml(_wizardState.reason || '') + '"></div>';
        html += '<div class="col-6"><input type="text" class="form-control form-control-sm" id="sigLocation" placeholder="Location" value="' + P.Utils.escapeHtml(_wizardState.location || '') + '"></div>';
        html += '<div class="col-6"><input type="text" class="form-control form-control-sm" id="sigContact" placeholder="Contact info" value="' + P.Utils.escapeHtml(_wizardState.contactInfo || '') + '"></div>';
        html += '</div></div>';

        // Unlocked fields
        html += '<div class="signing-option-group"><label>Unlocked Fields</label>';
        html += '<div class="signing-info-tip mb-1"><i class="fas fa-info-circle me-1"></i>Fields listed below will remain <strong>unlocked</strong> and can be modified after signing without invalidating the signature. All other fields will be locked.</div>';
        html += '<div class="sig-unlock-toolbar">';
        html += '<div class="sig-unlock-search-wrap"><i class="fas fa-search"></i><input type="text" class="form-control form-control-sm" id="sigUnlockSearch" placeholder="Search by name, type, or value..."></div>';
        html += '<button class="btn btn-outline-secondary btn-sm" id="sigUnlockPickBtn" title="Pick fields visually from the PDF viewer"><i class="fas fa-crosshairs me-1"></i>Pick from PDF</button>';
        html += '<button class="btn btn-outline-secondary btn-sm" id="sigUnlockAllBtn" title="Add all fields"><i class="fas fa-plus-circle me-1"></i>All</button>';
        html += '<button class="btn btn-outline-secondary btn-sm" id="sigUnlockClearBtn" title="Remove all"><i class="fas fa-times-circle me-1"></i>Clear</button>';
        html += '</div>';
        html += '<div class="sig-unlock-table-wrap" id="sigUnlockTableWrap"><div class="text-muted text-center" style="font-size:11px;padding:12px;">Loading form fields...</div></div>';
        html += '</div>';

        html += '</div>';
        return html;
    }

    // ── Step 4: Confirm ─────────────────────────────────────────────────

    function buildConfirmPanel() {
        var html = '<div class="signing-panel" data-panel="confirm">';
        html += '<h6><i class="fas fa-check-double me-1"></i>Review and Queue Signature</h6>';
        html += '<div class="signing-summary" id="sigSummary"></div>';
        html += '<div class="signing-info-tip mt-2"><i class="fas fa-info-circle me-1"></i>The signature will be added as a <strong>pending operation</strong>. Click <strong>Save</strong> in the toolbar to apply all pending changes including this signature.</div>';
        html += '</div>';
        return html;
    }

    function buildFooter() {
        var html = '<div class="signing-wizard-footer">';
        html += '<button class="btn btn-outline-secondary" id="sigPrevBtn" style="display:none;"><i class="fas fa-arrow-left me-1"></i>Back</button>';
        html += '<div></div>';
        html += '<button class="btn btn-accent" id="sigNextBtn">Next <i class="fas fa-arrow-right ms-1"></i></button>';
        html += '</div>';
        return html;
    }

    // ── Step navigation ─────────────────────────────────────────────────

    function activateStep(idx) {
        var steps = getSteps();
        _currentStep = idx;
        _modal.find('.signing-panel').removeClass('active');
        _modal.find('.signing-panel[data-panel="' + steps[idx] + '"]').addClass('active');

        // Rebuild step indicator (steps may have changed after type selection)
        _modal.find('.signing-steps').replaceWith(buildStepIndicator());

        // Footer buttons
        _modal.find('#sigPrevBtn').toggle(idx > 0);
        if (idx === steps.length - 1) {
            _modal.find('#sigNextBtn').html('<i class="fas fa-file-signature me-1"></i>Queue Signature');
        } else {
            _modal.find('#sigNextBtn').html('Next <i class="fas fa-arrow-right ms-1"></i>');
        }

        // Step-specific init
        var step = steps[idx];
        if (step === 'visual') { loadProfiles(); initDrawCanvas(); }
        if (step === 'certificate') { refreshKeys(); updateKeySizeOptions(); loadCscProviders(); }
        if (step === 'options') initUnlockedFieldsUI();
        if (step === 'confirm') renderSummary();
    }

    function nextStep() {
        captureCurrentStepState();
        if (!validateCurrentStep()) return;

        var steps = getSteps();
        if (_currentStep === steps.length - 1) {
            queueSignature();
            return;
        }
        activateStep(_currentStep + 1);
    }

    function prevStep() {
        captureCurrentStepState();
        if (_currentStep > 0) activateStep(_currentStep - 1);
    }

    // ── State capture ───────────────────────────────────────────────────

    function captureCurrentStepState() {
        var steps = getSteps();
        var step = steps[_currentStep];
        if (step === 'type') {
            _wizardState.signMode = $('input[name="sigSignMode"]:checked').val() || 'approval';
            _wizardState.docMdpLevel = parseInt($('input[name="sigDocMdp"]:checked').val(), 10) || 2;
        } else if (step === 'visual') {
            _wizardState.displayName = $('#sigDisplayName').val() || '';
            if (_wizardState.visualMode === 'draw' && P.SigningCanvas && P.SigningCanvas.hasStrokes()) {
                _wizardState.drawnImageBase64 = P.SigningCanvas.toDataURL();
                if (_wizardState.biometricEnabled && P.SigningCanvas.getBiometricData) {
                    _wizardState.biometricData = P.SigningCanvas.getBiometricData();
                }
            }
        } else if (step === 'options') {
            _wizardState.padesProfile = $('#sigPadesProfile').val() || 'B-B';
            _wizardState.reason = $('#sigReason').val() || '';
            _wizardState.location = $('#sigLocation').val() || '';
            _wizardState.contactInfo = $('#sigContact').val() || '';
            // TSA server
            var tsaVal = $('#sigTsaServer').val();
            if (tsaVal && _wizardState.padesProfile === 'B-T') {
                var tsaOpt = $('#sigTsaServer option:selected');
                _wizardState.tsaServerId = tsaOpt.data('tsa-id') || null;
                _wizardState.tsaUrl = tsaOpt.data('tsa-url') || tsaVal;
                _wizardState.tsaServerName = tsaOpt.data('tsa-name') || tsaVal;
            } else {
                _wizardState.tsaServerId = null;
                _wizardState.tsaUrl = null;
                _wizardState.tsaServerName = null;
            }
            // excludedFields is maintained live by the unlocked fields UI — no capture needed
        }
    }

    function validateCurrentStep() {
        var steps = getSteps();
        var step = steps[_currentStep];
        if (step === 'visual') {
            if (_wizardState.visualMode === 'text' && !_wizardState.displayName.trim()) {
                P.Utils.toast('Please enter a display name', 'warning');
                return false;
            }
            if (_wizardState.visualMode === 'image' && !_wizardState.imageDataBase64) {
                P.Utils.toast('Please upload an image', 'warning');
                return false;
            }
            if (_wizardState.visualMode === 'draw' && P.SigningCanvas && P.SigningCanvas.isEmpty()) {
                P.Utils.toast('Please draw your signature', 'warning');
                return false;
            }
        } else if (step === 'certificate') {
            if (!_wizardState.sessionKeyId) {
                P.Utils.toast('Please select a certificate (local or CSC remote)', 'warning');
                return false;
            }
        } else if (step === 'options') {
            if (_wizardState.padesProfile === 'B-T') {
                var tsaVal = $('#sigTsaServer').val();
                if (!tsaVal) {
                    P.Utils.toast('Please select a TSA server for PAdES B-T profile', 'warning');
                    return false;
                }
            }
        }
        return true;
    }

    // ── Event binding ───────────────────────────────────────────────────

    function bindEvents() {
        _modal.off('.sigwiz');

        _modal.on('click.sigwiz', '#sigNextBtn', nextStep);
        _modal.on('click.sigwiz', '#sigPrevBtn', prevStep);

        // Visual mode tabs
        _modal.on('click.sigwiz', '.signing-visual-tab', function () {
            var mode = $(this).data('vmode');
            _wizardState.visualMode = mode;
            _modal.find('.signing-visual-tab').removeClass('active');
            $(this).addClass('active');
            _modal.find('.signing-visual-content').removeClass('active');
            _modal.find('.signing-visual-content[data-vcontent="' + mode + '"]').addClass('active');
            if (mode === 'draw') initDrawCanvas();
        });

        // Font grid selection
        _modal.on('click.sigwiz', '.signing-font-option', function () {
            var fontId = $(this).data('font');
            _wizardState.fontName = fontId;
            _modal.find('.signing-font-option').removeClass('active');
            $(this).addClass('active');
            var fontEntry = SIGNING_FONTS.find(function (f) { return f.id === fontId; });
            if (fontEntry) {
                $('#sigFontPreview').css('font-family', "'" + fontEntry.css + "'")
                    .text($('#sigDisplayName').val() || 'Your Name');
            }
        });

        // Live preview update when display name changes
        _modal.on('input.sigwiz', '#sigDisplayName', function () {
            var name = $(this).val() || 'Your Name';
            $('#sigFontPreview').text(name);
            // Sync profile name if it was auto-filled or empty
            var $pn = $('#sigProfileName');
            var pnVal = $pn.val();
            if (!pnVal || pnVal === _wizardState.displayName) {
                $pn.val($(this).val());
                $('#sigSaveProfile').prop('disabled', !$(this).val().trim());
            }
            _wizardState.displayName = $(this).val() || '';
        });

        // Image upload
        _modal.on('click.sigwiz', '#sigImageDropZone', function (e) { if (!$(e.target).is('input')) document.getElementById('sigImageInput').click(); });
        _modal.on('change.sigwiz', '#sigImageInput', handleImageUpload);
        _modal.on('dragover.sigwiz', '#sigImageDropZone', function (e) { e.preventDefault(); $(this).addClass('dragover'); });
        _modal.on('dragleave.sigwiz', '#sigImageDropZone', function () { $(this).removeClass('dragover'); });
        _modal.on('drop.sigwiz', '#sigImageDropZone', function (e) {
            e.preventDefault();
            $(this).removeClass('dragover');
            var files = e.originalEvent.dataTransfer.files;
            if (files.length) readImageFile(files[0]);
        });

        // Draw canvas
        _modal.on('click.sigwiz', '#sigDrawUndo', function () { if (P.SigningCanvas) P.SigningCanvas.undoStroke(); });
        _modal.on('click.sigwiz', '#sigDrawClear', function () { if (P.SigningCanvas) P.SigningCanvas.clear(); });
        _modal.on('input.sigwiz', '#sigPenWidth', function () {
            var v = parseFloat($(this).val());
            _wizardState.drawPenWidth = v;
            $('#sigPenWidthVal').text(v);
            if (P.SigningCanvas) P.SigningCanvas.setPenWidth(v);
        });
        _modal.on('change.sigwiz', '#sigSmoothAlgo', function () {
            var algo = $(this).val();
            _wizardState.drawSmoothAlgo = algo;
            if (P.SigningCanvas) P.SigningCanvas.setSmoothingAlgorithm(algo);
        });
        _modal.on('input.sigwiz', '#sigSmoothing', function () {
            var v = parseFloat($(this).val());
            _wizardState.drawSmoothing = v;
            $('#sigSmoothVal').text(v);
            if (P.SigningCanvas) P.SigningCanvas.setSmoothingLevel(v);
        });
        _modal.on('change.sigwiz', '#sigBiometric', function () {
            var checked = $(this).is(':checked');
            _wizardState.biometricEnabled = checked;
            $('#sigBioFormat').prop('disabled', !checked);
            if (P.SigningCanvas) P.SigningCanvas.setBiometricEnabled(checked);
        });
        _modal.on('change.sigwiz', '#sigBioFormat', function () {
            _wizardState.bioFormat = $(this).val();
        });
        _modal.on('click.sigwiz', '#sigBioInspect', function (e) {
            e.stopPropagation();
            if (!P.SigningCanvas || !P.SigningCanvas.hasStrokes()) {
                P.Utils.toast(P.Utils.i18n('bio.inspect.nodata', 'Draw a signature first'), 'warning');
                return;
            }
            var bioData = P.SigningCanvas.getBiometricData();
            if (!bioData || !bioData.strokes || !bioData.strokes.length) {
                P.Utils.toast(P.Utils.i18n('bio.inspect.nodata', 'No biometric data recorded'), 'warning');
                return;
            }
            if (P.BioInspector) P.BioInspector.open(bioData);
        });

        // Certificate upload
        _modal.on('click.sigwiz', '#sigCertDropZone', function (e) { if (!$(e.target).is('input')) document.getElementById('sigCertInput').click(); });
        _modal.on('change.sigwiz', '#sigCertInput', handleCertUpload);
        _modal.on('dragover.sigwiz', '#sigCertDropZone', function (e) { e.preventDefault(); $(this).addClass('dragover'); });
        _modal.on('dragleave.sigwiz', '#sigCertDropZone', function () { $(this).removeClass('dragover'); });
        _modal.on('drop.sigwiz', '#sigCertDropZone', function (e) {
            e.preventDefault();
            $(this).removeClass('dragover');
            var files = e.originalEvent.dataTransfer.files;
            if (files.length) uploadCertFile(files[0], null);
        });
        _modal.on('click.sigwiz', '#sigCertUploadBtn', function () {
            var pwd = $('#sigCertPassword').val();
            var input = document.getElementById('sigCertInput');
            if (input.files.length) uploadCertFile(input.files[0], pwd);
        });

        // Self-signed generation
        _modal.on('click.sigwiz', '#sigGenerateSs', generateSelfSigned);
        _modal.on('change.sigwiz', '#sigSsAlgo', updateKeySizeOptions);

        // Sign mode toggle DocMDP visibility + update steps
        _modal.on('change.sigwiz', 'input[name="sigSignMode"]', function () {
            var isCert = $(this).val() === 'certification';
            _wizardState.signMode = isCert ? 'certification' : 'approval';
            if (isCert) _wizardState.visualMode = 'invisible';
            $('#sigDocMdpGroup').toggle(isCert);
            // Rebuild step indicator to reflect changed steps
            _modal.find('.signing-steps').replaceWith(buildStepIndicator());
        });

        // PAdES profile change — show/hide TSA panel
        _modal.on('change.sigwiz', '#sigPadesProfile', function () {
            var isBT = $(this).val() === 'B-T';
            $('#sigTsaGroup').toggle(isBT);
            if (isBT && _tsaServers.length === 0) loadTsaServers();
        });

        // TSA server selection change
        _modal.on('change.sigwiz', '#sigTsaServer', function () {
            var selId = $(this).val();
            var opt = $(this).find('option:selected');
            _wizardState.tsaServerId = opt.data('tsa-id') || null;
            _wizardState.tsaUrl = opt.data('tsa-url') || null;
            _wizardState.tsaServerName = opt.data('tsa-name') || null;
            updateTsaInfo();
        });

        // TSA refresh/re-probe
        _modal.on('click.sigwiz', '#sigTsaRefresh', function () {
            $.post('/api/signing/tsa/probe').done(function () {
                P.Utils.toast('Re-probing TSA servers...', 'info');
                loadTsaServers();
            });
        });

        // ── CSC Remote Signing events ───────────────────────────────────
        _modal.on('change.sigwiz', '#sigCscProvider', function () {
            var pid = $(this).val();
            if (!pid) {
                $('#sigCscConnectForm').hide();
                $('#sigCscProviderInfo').html('');
                $('#sigCscStatus').html('');
                $('#sigCscCredentials').hide();
                $('#sigCscOtpSection').hide();
                _wizardState.cscProviderId = null;
                _wizardState.cscConnected = false;
                return;
            }
            _wizardState.cscProviderId = pid;
            _wizardState.cscConnected = false;
            showCscProviderInfo(pid);
            $('#sigCscConnectForm').show();
            $('#sigCscCredentials').hide();
            $('#sigCscOtpSection').hide();
            $('#sigCscStatus').html('');
        });
        _modal.on('click.sigwiz', '#sigCscConnectBtn', connectCscProvider);
        _modal.on('click.sigwiz', '#sigCscSendOtp', sendCscOtp);
        _modal.on('click.sigwiz', '#sigCscAddProvider', showAddCscProviderDialog);
        _modal.on('click.sigwiz', '.csc-credential-item', function () {
            var credId = $(this).data('cred-id');
            _wizardState.cscCredentialId = credId;
            _wizardState.cscCertMode = 'csc';
            _wizardState.sessionKeyId = 'csc:' + credId; // sentinel value for validation
            _modal.find('.csc-credential-item').removeClass('selected');
            $(this).addClass('selected');
            // Deselect local keys
            _modal.find('.signing-key-item').removeClass('selected');
            // Show OTP if SCAL2
            var scal = $(this).data('cred-scal');
            if (scal === '2') {
                $('#sigCscOtpSection').show();
            } else {
                $('#sigCscOtpSection').hide();
            }
        });

        // Profile save
        _modal.on('input.sigwiz', '#sigProfileName', function () { $('#sigSaveProfile').prop('disabled', !$(this).val().trim()); });
        _modal.on('click.sigwiz', '#sigSaveProfile', saveProfile);

        // Key selection (local)
        _modal.on('click.sigwiz', '.signing-key-item', function () {
            var keyId = $(this).data('key-id');
            _wizardState.sessionKeyId = keyId;
            _wizardState.cscCertMode = 'local';
            _wizardState.cscCredentialId = null;
            _modal.find('.signing-key-item').removeClass('selected');
            $(this).addClass('selected');
            // Deselect CSC credentials
            _modal.find('.csc-credential-item').removeClass('selected');
        });

        // Key delete
        _modal.on('click.sigwiz', '.signing-key-delete', function (e) {
            e.stopPropagation();
            var keyId = $(this).closest('.signing-key-item').data('key-id');
            deleteKey(keyId);
        });

        // Key export
        _modal.on('click.sigwiz', '.signing-key-export', function (e) {
            e.stopPropagation();
            var keyId = $(this).closest('.signing-key-item').data('key-id');
            window.open('/api/signing/' + P.state.sessionId + '/export-key/' + keyId + '?password=changeit', '_blank');
        });
    }

    // ── Certificate operations ──────────────────────────────────────────

    function handleCertUpload() {
        var input = document.getElementById('sigCertInput');
        if (!input.files.length) return;
        var file = input.files[0];
        var name = file.name.toLowerCase();
        if (name.endsWith('.p12') || name.endsWith('.pfx') || name.endsWith('.jks')) {
            $('#sigCertPasswordRow').show();
        } else {
            uploadCertFile(file, null);
        }
    }

    function uploadCertFile(file, password) {
        var fd = new FormData();
        fd.append('file', file);
        if (password) fd.append('password', password);

        P.Utils.toast('Uploading key material...', 'info');
        $.ajax({
            url: '/api/signing/' + P.state.sessionId + '/upload-key',
            method: 'POST',
            data: fd,
            processData: false,
            contentType: false
        }).done(function (data) {
            P.Utils.toast('Key material uploaded', 'success');
            $('#sigCertPasswordRow').hide();
            $('#sigCertPassword').val('');
            refreshKeys();
            if (data.readyToSign) _wizardState.sessionKeyId = data.sessionKeyId;
        }).fail(function (xhr) {
            var msg = xhr.responseJSON ? xhr.responseJSON.error : 'Upload failed';
            if (msg && msg.indexOf('password') >= 0) {
                $('#sigCertPasswordRow').show();
                P.Utils.toast('Password required or incorrect', 'warning');
            } else {
                P.Utils.toast(msg, 'danger');
            }
        });
    }

    var SIG_ALGO_OPTIONS = {
        RSA: [
            { value: '', label: 'Auto (match key size)' },
            { value: 'SHA256withRSA', label: 'SHA-256 + PKCS#1' },
            { value: 'SHA384withRSA', label: 'SHA-384 + PKCS#1' },
            { value: 'SHA512withRSA', label: 'SHA-512 + PKCS#1' },
            { value: 'SHA256withRSAandMGF1', label: 'SHA-256 + PSS' },
            { value: 'SHA384withRSAandMGF1', label: 'SHA-384 + PSS' },
            { value: 'SHA512withRSAandMGF1', label: 'SHA-512 + PSS' }
        ],
        EC: [
            { value: '', label: 'Auto (match curve)' },
            { value: 'SHA256withECDSA', label: 'SHA-256' },
            { value: 'SHA384withECDSA', label: 'SHA-384' },
            { value: 'SHA512withECDSA', label: 'SHA-512' }
        ]
    };

    var KEY_SIZE_OPTIONS = {
        RSA: [
            { value: 2048, label: '2048 bit' },
            { value: 3072, label: '3072 bit' },
            { value: 4096, label: '4096 bit', selected: true },
            { value: 8192, label: '8192 bit' }
        ],
        EC: [
            { value: 256, label: 'P-256 (secp256r1)', selected: true },
            { value: 384, label: 'P-384 (secp384r1)' },
            { value: 521, label: 'P-521 (secp521r1)' }
        ]
    };

    var ALGO_HINTS = {
        RSA:     { icon: 'fa-shield-halved', cls: 'hint-good', text: 'RSA: Maximum PDF reader compatibility. Use 3072+ for post-2025 BSI compliance. 4096 recommended for long-term.' },
        EC:      { icon: 'fa-bolt', cls: 'hint-good', text: 'ECDSA: Fast signing, small signatures. Supported by Acrobat 2017+. BSI & NIST recommended.' },
        ED25519: { icon: 'fa-flask', cls: 'hint-warn', text: 'Ed25519: Modern, constant-time, very fast. Experimental — not yet standardized in PDF/PAdES. Use for testing only.' },
        ED448:   { icon: 'fa-flask', cls: 'hint-warn', text: 'Ed448: Strongest EdDSA variant (224-bit security). Experimental — no standard PDF reader support.' }
    };

    function updateKeySizeOptions() {
        var algo = $('#sigSsAlgo').val();
        var $keySize = $('#sigSsKeySize');
        var $sigAlgo = $('#sigSsSigAlgo');

        if (algo === 'ED25519' || algo === 'ED448') {
            $keySize.html('<option value="0">Fixed</option>').prop('disabled', true);
            $sigAlgo.html('<option value="">Built-in</option>').prop('disabled', true);
        } else {
            $keySize.prop('disabled', false);
            $sigAlgo.prop('disabled', false);
            var sizes = KEY_SIZE_OPTIONS[algo] || KEY_SIZE_OPTIONS.RSA;
            var sizeHtml = '';
            for (var i = 0; i < sizes.length; i++) {
                sizeHtml += '<option value="' + sizes[i].value + '"' + (sizes[i].selected ? ' selected' : '') + '>' + sizes[i].label + '</option>';
            }
            $keySize.html(sizeHtml);

            var sigs = SIG_ALGO_OPTIONS[algo] || SIG_ALGO_OPTIONS.RSA;
            var sigHtml = '';
            for (var j = 0; j < sigs.length; j++) {
                sigHtml += '<option value="' + sigs[j].value + '">' + sigs[j].label + '</option>';
            }
            $sigAlgo.html(sigHtml);
        }

        // Update hint
        var hint = ALGO_HINTS[algo] || ALGO_HINTS.RSA;
        $('#sigAlgoHint').html('<div class="sig-algo-hint-inner ' + hint.cls + '"><i class="fas ' + hint.icon + ' me-1"></i>' + hint.text + '</div>');
    }

    function generateSelfSigned() {
        var cn = $('#sigSsCn').val();
        if (!cn || !cn.trim()) {
            P.Utils.toast('Common Name is required', 'warning');
            return;
        }
        var algo = $('#sigSsAlgo').val();
        var keySize = parseInt($('#sigSsKeySize').val(), 10) || 0;
        var sigAlgo = $('#sigSsSigAlgo').val() || null;

        var body = {
            commonName: cn.trim(),
            organization: $('#sigSsOrg').val() || null,
            country: $('#sigSsCountry').val() || null,
            validityDays: parseInt($('#sigSsDays').val(), 10) || 365,
            keyAlgorithm: algo,
            keySize: keySize,
            signatureAlgorithm: sigAlgo
        };

        $('#sigGenerateSs').prop('disabled', true).html('<i class="fas fa-spinner fa-spin me-1"></i>Generating...');
        $.ajax({
            url: '/api/signing/' + P.state.sessionId + '/generate-self-signed',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(body)
        }).done(function (data) {
            P.Utils.toast('Self-signed certificate generated', 'success');
            _wizardState.sessionKeyId = data.sessionKeyId;
            refreshKeys();
        }).fail(function (xhr) {
            P.Utils.toast(xhr.responseJSON ? xhr.responseJSON.error : 'Generation failed', 'danger');
        }).always(function () {
            $('#sigGenerateSs').prop('disabled', false).html('<i class="fas fa-wand-magic-sparkles me-1"></i>Generate Certificate');
        });
    }

    function refreshKeys() {
        $.get('/api/signing/' + P.state.sessionId + '/keys').done(function (keys) {
            renderKeysList(keys);
        });
    }

    function refreshKeysInBackground() {
        $.get('/api/signing/' + P.state.sessionId + '/keys').done(function (keys) {
            if (keys.length) renderKeysList(keys);
        });
    }

    function renderKeysList(keys) {
        var $list = $('#sigKeysList');
        if (!$list.length) return;
        if (!keys || !keys.length) {
            $list.html('<div class="text-muted text-center" style="font-size:11px;">No certificates loaded</div>');
            return;
        }
        var html = '';
        for (var i = 0; i < keys.length; i++) {
            var k = keys[i];
            var sel = k.sessionKeyId === _wizardState.sessionKeyId ? ' selected' : '';
            html += '<div class="signing-key-item' + sel + '" data-key-id="' + k.sessionKeyId + '">';
            html += '<div>';
            html += '<div class="key-subject">' + P.Utils.escapeHtml(formatDN(k.subjectDN)) + '</div>';
            if (k.issuerDN && k.issuerDN !== k.subjectDN) html += '<div class="key-issuer">Issued by: ' + P.Utils.escapeHtml(formatDN(k.issuerDN)) + '</div>';
            var meta = [];
            if (k.keyAlgorithm) { var kd = k.keyAlgorithm; if (k.keySize) kd += ' ' + k.keySize + '-bit'; meta.push(kd); }
            if (k.notBefore && k.notAfter) meta.push(k.notBefore + ' \u2014 ' + k.notAfter);
            if (k.chainLength > 1) meta.push('Chain: ' + k.chainLength);
            if (k.serialNumber) meta.push('S/N: ' + k.serialNumber);
            if (meta.length) html += '<div class="key-meta">' + P.Utils.escapeHtml(meta.join(' \u00b7 ')) + '</div>';
            html += '<div style="font-size:10px;color:' + (k.readyToSign ? 'var(--c-ok)' : 'var(--c-err)') + ';">';
            html += k.readyToSign ? '<i class="fas fa-check-circle me-1"></i>Ready to sign' : '<i class="fas fa-exclamation-circle me-1"></i>' + (k.missingElements || []).join(', ');
            html += '</div></div>';
            html += '<div class="key-actions">';
            html += '<button class="btn btn-outline-secondary btn-sm signing-key-export" title="Download as PKCS12"><i class="fas fa-download"></i><span class="dl-filetype">P12</span></button>';
            html += '<button class="btn btn-outline-danger btn-sm signing-key-delete" title="Remove"><i class="fas fa-trash"></i></button>';
            html += '</div></div>';
        }
        $list.html(html);
    }

    function deleteKey(keyId) {
        $.ajax({
            url: '/api/signing/' + P.state.sessionId + '/key/' + keyId,
            method: 'DELETE'
        }).done(function () {
            if (_wizardState.sessionKeyId === keyId) _wizardState.sessionKeyId = null;
            refreshKeys();
        });
    }

    // ── Image handling ──────────────────────────────────────────────────

    function handleImageUpload() {
        var input = document.getElementById('sigImageInput');
        if (input.files.length) readImageFile(input.files[0]);
    }

    function readImageFile(file) {
        var reader = new FileReader();
        reader.onload = function (e) {
            _wizardState.imageDataBase64 = e.target.result;
            var $preview = $('#sigImagePreview');
            $preview.attr('src', e.target.result).show();
        };
        reader.readAsDataURL(file);
    }

    // ── Draw canvas init ────────────────────────────────────────────────

    function initDrawCanvas() {
        var canvas = document.getElementById('sigDrawCanvas');
        if (canvas && P.SigningCanvas) {
            P.SigningCanvas.init(canvas);
            P.SigningCanvas.setPenWidth(_wizardState.drawPenWidth || 2.0);
            P.SigningCanvas.setSmoothingAlgorithm(_wizardState.drawSmoothAlgo || 'chaikin');
            P.SigningCanvas.setSmoothingLevel(_wizardState.drawSmoothing != null ? _wizardState.drawSmoothing : 0.5);
            P.SigningCanvas.setBiometricEnabled(_wizardState.biometricEnabled !== false);
            if (_wizardState.drawnImageBase64 && P.SigningCanvas.loadImage) {
                P.SigningCanvas.loadImage(_wizardState.drawnImageBase64);
            }
        }
    }

    // ── Unlocked fields (replaces old excluded fields) ─────────────────

    var _allFieldInfos = []; // cached field metadata [{fullName, fieldType, value, page, readOnly, required, subType}]
    var _fieldPickerActive = false;
    var _fieldPickerCallback = null;

    function collectFieldInfos() {
        var infos = [];
        if (!P.state.treeData) return infos;
        (function walk(node) {
            if (!node) return;
            if (node.nodeCategory === 'field' && node.properties && node.properties.FullName) {
                var fn = node.properties.FullName;
                if (fn !== _fieldName) {
                    infos.push({
                        fullName: fn,
                        fieldType: node.properties.FieldType || '',
                        subType: node.properties.FieldSubType || '',
                        value: node.properties.Value || '',
                        page: node.pageIndex >= 0 ? node.pageIndex + 1 : '',
                        readOnly: node.properties.ReadOnly === 'true',
                        required: node.properties.Required === 'true'
                    });
                }
            }
            if (node.children) node.children.forEach(walk);
        })(P.state.treeData);
        return infos;
    }

    function initUnlockedFieldsUI() {
        _allFieldInfos = collectFieldInfos();
        renderUnlockedTable();
        bindUnlockedEvents();
    }

    function renderUnlockedTable(filterText) {
        var $wrap = $('#sigUnlockTableWrap');
        if (!$wrap.length) return;

        if (!_allFieldInfos.length) {
            $wrap.html('<div class="text-muted text-center" style="font-size:11px;padding:12px;"><i class="fas fa-file fa-2x mb-2 d-block"></i>No form fields found in this PDF</div>');
            return;
        }

        var excluded = _wizardState.excludedFields || [];
        var filter = (filterText || '').toLowerCase().trim();

        // Split into unlocked (in excludedFields) and available (not in excludedFields)
        var unlocked = [];
        var available = [];
        for (var i = 0; i < _allFieldInfos.length; i++) {
            var f = _allFieldInfos[i];
            var matchesFilter = !filter ||
                f.fullName.toLowerCase().indexOf(filter) >= 0 ||
                f.fieldType.toLowerCase().indexOf(filter) >= 0 ||
                f.subType.toLowerCase().indexOf(filter) >= 0 ||
                f.value.toLowerCase().indexOf(filter) >= 0;
            if (!matchesFilter) continue;
            if (excluded.indexOf(f.fullName) >= 0) {
                unlocked.push(f);
            } else {
                available.push(f);
            }
        }

        var html = '';

        // Unlocked fields section
        html += '<div class="sig-unlock-section">';
        html += '<div class="sig-unlock-section-header"><i class="fas fa-lock-open me-1"></i>Unlocked (' + unlocked.length + ')</div>';
        if (unlocked.length) {
            html += '<table class="sig-unlock-field-table"><thead><tr>';
            html += '<th>Name</th><th>Type</th><th>Value</th><th>Pg</th><th></th>';
            html += '</tr></thead><tbody>';
            for (var u = 0; u < unlocked.length; u++) {
                html += buildFieldRow(unlocked[u], true);
            }
            html += '</tbody></table>';
        } else {
            html += '<div class="sig-unlock-empty">No unlocked fields' + (filter ? ' matching filter' : '') + ' — all fields will be locked</div>';
        }
        html += '</div>';

        // Available (locked) fields section
        html += '<div class="sig-unlock-section">';
        html += '<div class="sig-unlock-section-header"><i class="fas fa-lock me-1"></i>Locked (' + available.length + ')</div>';
        if (available.length) {
            html += '<table class="sig-unlock-field-table"><thead><tr>';
            html += '<th>Name</th><th>Type</th><th>Value</th><th>Pg</th><th></th>';
            html += '</tr></thead><tbody>';
            for (var a = 0; a < available.length; a++) {
                html += buildFieldRow(available[a], false);
            }
            html += '</tbody></table>';
        } else {
            html += '<div class="sig-unlock-empty">No locked fields' + (filter ? ' matching filter' : '') + '</div>';
        }
        html += '</div>';

        $wrap.html(html);
    }

    function buildFieldRow(field, isUnlocked) {
        var typeIcon = getFieldTypeIcon(field.fieldType, field.subType);
        var badges = '';
        if (field.readOnly) badges += '<span class="sig-field-badge ro" title="Read-only">RO</span>';
        if (field.required) badges += '<span class="sig-field-badge req" title="Required">Req</span>';
        var actionIcon = isUnlocked ? 'fa-lock' : 'fa-lock-open';
        var actionTitle = isUnlocked ? 'Lock this field' : 'Unlock this field';
        var actionClass = isUnlocked ? 'sig-unlock-remove-btn' : 'sig-unlock-add-btn';
        return '<tr class="sig-unlock-row" data-field="' + P.Utils.escapeHtml(field.fullName) + '">' +
            '<td class="sig-field-name" title="' + P.Utils.escapeHtml(field.fullName) + '"><i class="fas ' + typeIcon + ' me-1"></i>' + P.Utils.escapeHtml(field.fullName) + badges + '</td>' +
            '<td>' + P.Utils.escapeHtml(field.subType || field.fieldType) + '</td>' +
            '<td class="sig-field-val" title="' + P.Utils.escapeHtml(field.value) + '">' + P.Utils.escapeHtml(truncate(field.value, 30)) + '</td>' +
            '<td>' + (field.page || '') + '</td>' +
            '<td><button class="btn btn-sm ' + actionClass + '" title="' + actionTitle + '"><i class="fas ' + actionIcon + '"></i></button></td>' +
            '</tr>';
    }

    function truncate(str, max) {
        return str && str.length > max ? str.substring(0, max) + '...' : (str || '');
    }

    function getFieldTypeIcon(fieldType, subType) {
        var icons = { Tx: 'fa-font', Btn: 'fa-square-check', Ch: 'fa-list', Sig: 'fa-signature' };
        var sub = { checkbox: 'fa-square-check', radio: 'fa-circle-dot', combobox: 'fa-caret-down', listbox: 'fa-list-ul', textarea: 'fa-align-left' };
        return sub[subType] || icons[fieldType] || 'fa-input-text';
    }

    function bindUnlockedEvents() {
        var $wrap = $('#sigUnlockTableWrap');
        $wrap.off('click.sigUnlock').on('click.sigUnlock', '.sig-unlock-add-btn', function (e) {
            e.stopPropagation();
            var name = $(this).closest('.sig-unlock-row').data('field');
            if (!name) return;
            if (!_wizardState.excludedFields) _wizardState.excludedFields = [];
            if (_wizardState.excludedFields.indexOf(name) < 0) _wizardState.excludedFields.push(name);
            renderUnlockedTable($('#sigUnlockSearch').val());
        });
        $wrap.off('click.sigLock').on('click.sigLock', '.sig-unlock-remove-btn', function (e) {
            e.stopPropagation();
            var name = $(this).closest('.sig-unlock-row').data('field');
            if (!name) return;
            _wizardState.excludedFields = (_wizardState.excludedFields || []).filter(function (n) { return n !== name; });
            renderUnlockedTable($('#sigUnlockSearch').val());
        });

        // Row click navigates to field in viewer
        $wrap.off('click.sigNav').on('click.sigNav', '.sig-unlock-row', function () {
            var name = $(this).data('field');
            if (!name || !P.state.treeData) return;
            var found = null;
            (function walk(n) {
                if (found) return;
                if (n.nodeCategory === 'field' && n.properties && n.properties.FullName === name) { found = n; return; }
                if (n.children) n.children.forEach(walk);
            })(P.state.treeData);
            if (found && found.pageIndex >= 0) {
                if (P.Viewer) {
                    P.Viewer.clearHighlights();
                    if (found.boundingBox) P.Viewer.highlight(found.pageIndex, found.boundingBox, { locator: true });
                    else P.Viewer.scrollToPage(found.pageIndex);
                }
            }
        });

        // Search
        $('#sigUnlockSearch').off('input.sigUnlock').on('input.sigUnlock', function () {
            renderUnlockedTable($(this).val());
        });

        // All / Clear buttons
        $('#sigUnlockAllBtn').off('click.sig').on('click.sig', function () {
            _wizardState.excludedFields = _allFieldInfos.map(function (f) { return f.fullName; });
            renderUnlockedTable($('#sigUnlockSearch').val());
        });
        $('#sigUnlockClearBtn').off('click.sig').on('click.sig', function () {
            _wizardState.excludedFields = [];
            renderUnlockedTable($('#sigUnlockSearch').val());
        });

        // Pick from PDF button
        $('#sigUnlockPickBtn').off('click.sig').on('click.sig', function () {
            enterFieldPickerMode();
        });
    }

    // ── Visual field picker mode ─────────────────────────────────────────

    function enterFieldPickerMode() {
        if (!_bsModal || !_modal) return;
        _fieldPickerActive = true;

        // Hide the modal
        _modal.addClass('d-none');

        // Show floating picker toolbar
        var $toolbar = $('<div>', { id: 'sigFieldPickerToolbar', 'class': 'sig-field-picker-toolbar' });
        var tbHtml = '<div class="sig-picker-tb-inner">';
        tbHtml += '<span class="sig-picker-tb-title"><i class="fas fa-crosshairs me-1"></i>Field Picker</span>';
        tbHtml += '<span class="sig-picker-tb-hint">Click fields in the PDF or tree to toggle unlock. Hold Ctrl/Cmd for multi-select.</span>';
        tbHtml += '<span class="sig-picker-tb-sep"></span>';
        tbHtml += '<span class="sig-picker-tb-count" id="sigPickerCount">' + (_wizardState.excludedFields || []).length + ' unlocked</span>';
        tbHtml += '<span class="sig-picker-tb-sep"></span>';
        tbHtml += '<button class="btn btn-sm btn-outline-secondary" id="sigPickerSelectAll" title="Unlock all fields"><i class="fas fa-check-double me-1"></i>All</button>';
        tbHtml += '<button class="btn btn-sm btn-outline-secondary" id="sigPickerSelectNone" title="Lock all fields"><i class="fas fa-times me-1"></i>None</button>';
        tbHtml += '<span class="sig-picker-tb-sep"></span>';
        tbHtml += '<select class="form-select form-select-sm" id="sigPickerTypeFilter" title="Filter by field type" style="width:auto;min-width:100px;">';
        tbHtml += '<option value="">All types</option>';
        tbHtml += '<option value="Tx">Text</option>';
        tbHtml += '<option value="Btn">Buttons</option>';
        tbHtml += '<option value="Ch">Choice</option>';
        tbHtml += '<option value="Sig">Signature</option>';
        tbHtml += '</select>';
        tbHtml += '<button class="btn btn-sm btn-primary" id="sigPickerDone"><i class="fas fa-check me-1"></i>Done</button>';
        tbHtml += '</div>';
        $toolbar.html(tbHtml);
        $('body').append($toolbar);

        // Render field highlights on all pages
        renderPickerHighlights();

        // Bind picker events
        $toolbar.on('click', '#sigPickerDone', exitFieldPickerMode);
        $toolbar.on('click', '#sigPickerSelectAll', function () {
            var typeFilter = $('#sigPickerTypeFilter').val();
            _wizardState.excludedFields = _allFieldInfos
                .filter(function (f) { return !typeFilter || f.fieldType === typeFilter; })
                .map(function (f) { return f.fullName; });
            renderPickerHighlights();
            updatePickerCount();
        });
        $toolbar.on('click', '#sigPickerSelectNone', function () {
            var typeFilter = $('#sigPickerTypeFilter').val();
            if (typeFilter) {
                _wizardState.excludedFields = (_wizardState.excludedFields || []).filter(function (n) {
                    var fi = _allFieldInfos.find(function (f) { return f.fullName === n; });
                    return fi && fi.fieldType !== typeFilter;
                });
            } else {
                _wizardState.excludedFields = [];
            }
            renderPickerHighlights();
            updatePickerCount();
        });
        $toolbar.on('change', '#sigPickerTypeFilter', function () {
            renderPickerHighlights();
        });

        // Listen for field clicks on the viewer
        $(document).on('click.sigPicker', '.sig-picker-overlay', function (e) {
            e.stopPropagation();
            var name = $(this).data('field-name');
            if (!name) return;
            toggleFieldUnlock(name);
            $(this).toggleClass('sig-picker-selected', (_wizardState.excludedFields || []).indexOf(name) >= 0);
            updatePickerCount();
        });

        // ESC to finish
        $(document).on('keydown.sigPicker', function (e) {
            if (e.key === 'Escape') exitFieldPickerMode();
        });
    }

    function exitFieldPickerMode() {
        _fieldPickerActive = false;
        $('#sigFieldPickerToolbar').remove();
        $('.sig-picker-overlay').remove();
        $(document).off('click.sigPicker keydown.sigPicker');

        // Show the modal again
        if (_modal) {
            _modal.removeClass('d-none');
            renderUnlockedTable($('#sigUnlockSearch').val());
        }
    }

    function toggleFieldUnlock(fullName) {
        if (!_wizardState.excludedFields) _wizardState.excludedFields = [];
        var idx = _wizardState.excludedFields.indexOf(fullName);
        if (idx >= 0) _wizardState.excludedFields.splice(idx, 1);
        else _wizardState.excludedFields.push(fullName);
    }

    function updatePickerCount() {
        $('#sigPickerCount').text((_wizardState.excludedFields || []).length + ' unlocked');
    }

    function renderPickerHighlights() {
        $('.sig-picker-overlay').remove();
        if (!P.state.treeData) return;
        var typeFilter = $('#sigPickerTypeFilter').val() || '';
        var excluded = _wizardState.excludedFields || [];

        (function walk(node) {
            if (!node) return;
            if (node.nodeCategory === 'field' && node.properties && node.properties.FullName &&
                node.properties.FullName !== _fieldName && node.pageIndex >= 0 && node.boundingBox) {
                var fn = node.properties.FullName;
                var ft = node.properties.FieldType || '';
                if (typeFilter && ft !== typeFilter) { if (node.children) node.children.forEach(walk); return; }

                var isUnlocked = excluded.indexOf(fn) >= 0;
                var $pageWrapper = $('.pdf-page-wrapper[data-page-index="' + node.pageIndex + '"]');
                if (!$pageWrapper.length) { if (node.children) node.children.forEach(walk); return; }

                var canvas = $pageWrapper.find('canvas')[0];
                if (!canvas) { if (node.children) node.children.forEach(walk); return; }

                var scaleX = canvas.clientWidth / (canvas.width / (window.devicePixelRatio || 1));
                var scaleY = canvas.clientHeight / (canvas.height / (window.devicePixelRatio || 1));
                var bb = node.boundingBox;
                var $overlay = $('<div>', {
                    'class': 'sig-picker-overlay' + (isUnlocked ? ' sig-picker-selected' : ''),
                    'data-field-name': fn
                });
                $overlay.css({
                    left: bb[0] * scaleX,
                    top: bb[1] * scaleY,
                    width: (bb[2] - bb[0]) * scaleX,
                    height: (bb[3] - bb[1]) * scaleY
                });
                $overlay.attr('title', fn + ' [' + ft + ']' + (isUnlocked ? ' — UNLOCKED' : ' — locked'));
                $pageWrapper.css('position', 'relative').append($overlay);
            }
            if (node.children) node.children.forEach(walk);
        })(P.state.treeData);
    }

    function walkTree(node, fn) {
        if (!node) return;
        fn(node);
        if (node.children) {
            for (var i = 0; i < node.children.length; i++) {
                walkTree(node.children[i], fn);
            }
        }
    }

    // ── Profiles ────────────────────────────────────────────────────────

    function loadProfiles() {
        if (!P.SigningStore) return;
        P.SigningStore.list().then(function (profiles) {
            var $list = $('#sigProfilesList');
            if (!profiles.length) {
                $list.html('<div class="text-muted text-center" style="font-size:11px;">No saved profiles</div>');
                return;
            }
            var html = '';
            for (var i = 0; i < profiles.length; i++) {
                var p = profiles[i];
                html += '<div class="signing-profile-item" data-profile-id="' + p.id + '">';
                // Mode icon
                html += '<span class="profile-mode-icon"><i class="fas ' + profileModeIcon(p.visualMode) + '"></i></span>';
                // Info column: name + inline metadata
                html += '<div class="profile-info">';
                html += '<span class="profile-name">' + P.Utils.escapeHtml(p.name || 'Profile ' + p.id) + '</span>';
                var metaParts = [profileModeLabel(p.visualMode)];
                if (p.visualMode === 'text') {
                    var fe = SIGNING_FONTS.find(function (f) { return f.id === p.fontName; });
                    if (fe) metaParts.push(P.Utils.escapeHtml(fe.label));
                }
                if (p.displayName) metaParts.push('"' + P.Utils.escapeHtml(p.displayName) + '"');
                if (p.imageWidth && p.imageHeight) metaParts.push(p.imageWidth + 'x' + p.imageHeight);
                if (p.imageSizeEstimate) metaParts.push('~' + formatProfileBytes(p.imageSizeEstimate));
                html += '<span class="profile-meta">' + metaParts.join(' &middot; ') + '</span>';
                var timeParts = [];
                if (p.createdAt) timeParts.push('Created ' + formatProfileDate(p.createdAt));
                if (p.lastUsedAt) timeParts.push('Used ' + formatProfileDate(p.lastUsedAt));
                if (p.useCount) timeParts.push(p.useCount + 'x');
                if (timeParts.length) html += '<span class="profile-meta">' + timeParts.join(' &middot; ') + '</span>';
                html += '</div>';
                html += '<button class="btn btn-outline-danger btn-sm btn-del profile-del"><i class="fas fa-trash"></i></button>';
                html += '</div>';
            }
            // Tooltip popup element (reused on hover)
            html += '<div class="signing-profile-tooltip" id="sigProfileTooltip" style="display:none;"></div>';
            $list.html(html);

            // Hover tooltip
            $list.find('.signing-profile-item').off('mouseenter.sig mouseleave.sig').on('mouseenter.sig', function (e) {
                var pid = $(this).data('profile-id');
                var prof = profiles.find(function (pp) { return pp.id === pid; });
                if (prof) showProfileTooltip($(this), prof);
            }).on('mouseleave.sig', function () {
                hideProfileTooltip();
            });

            $list.find('.signing-profile-item').off('click').on('click', function (e) {
                if ($(e.target).closest('.profile-del').length) return;
                var pid = $(this).data('profile-id');
                P.SigningStore.get(pid).then(function (p) {
                    if (!p) return;
                    _wizardState.visualMode = p.visualMode || 'text';
                    _wizardState.displayName = p.displayName || '';
                    _wizardState.fontName = p.fontName || 'Helvetica';
                    _wizardState.imageDataBase64 = p.imageDataBase64 || null;
                    _wizardState.drawnImageBase64 = p.drawnImageBase64 || null;
                    if (p.drawSmoothAlgo) _wizardState.drawSmoothAlgo = p.drawSmoothAlgo;
                    if (p.drawSmoothing != null) _wizardState.drawSmoothing = p.drawSmoothing;
                    if (p.drawPenWidth) _wizardState.drawPenWidth = p.drawPenWidth;
                    if (p.biometricEnabled != null) _wizardState.biometricEnabled = p.biometricEnabled;
                    if (p.bioFormat) _wizardState.bioFormat = p.bioFormat;
                    P.SigningStore.recordUsage(pid);
                    renderWizard();
                    P.Utils.toast('Profile loaded', 'success');
                });
            });

            $list.find('.profile-del').off('click').on('click', function () {
                var pid = $(this).closest('.signing-profile-item').data('profile-id');
                P.SigningStore.remove(pid).then(function () { loadProfiles(); });
            });
        }).catch(function () {
            $('#sigProfilesList').html('<div class="text-muted text-center" style="font-size:11px;">IndexedDB not available</div>');
        });
    }

    function showProfileTooltip($el, prof) {
        var $tip = $('#sigProfileTooltip');
        if (!$tip.length) return;
        var imgSrc = prof.drawnImageBase64 || prof.imageDataBase64 || prof.thumbnailDataUrl;
        if (!imgSrc) {
            $tip.hide();
            return;
        }
        $tip.html('<div class="sig-tooltip-inner"><img class="sig-tooltip-img" src="' + imgSrc + '" alt=""></div>');

        var rect = $el[0].getBoundingClientRect();
        var listRect = $el.closest('.signing-profiles-list')[0].getBoundingClientRect();
        $tip.css({
            display: 'block',
            left: listRect.right + 8,
            top: rect.top
        });
    }

    function hideProfileTooltip() {
        $('#sigProfileTooltip').hide();
    }

    function profileModeIcon(mode) {
        var icons = { text: 'fa-font', image: 'fa-image', draw: 'fa-pen', invisible: 'fa-eye-slash' };
        return icons[mode] || 'fa-signature';
    }

    function profileModeLabel(mode) {
        var labels = { text: 'Text', image: 'Image', draw: 'Drawn', invisible: 'Invisible' };
        return labels[mode] || mode || 'Unknown';
    }

    function formatProfileDate(ts) {
        if (!ts) return '';
        var d = new Date(ts);
        var now = new Date();
        var diffMs = now - d;
        if (diffMs < 60000) return 'just now';
        if (diffMs < 3600000) return Math.floor(diffMs / 60000) + 'm ago';
        if (diffMs < 86400000) return Math.floor(diffMs / 3600000) + 'h ago';
        if (diffMs < 604800000) return Math.floor(diffMs / 86400000) + 'd ago';
        return d.toLocaleDateString();
    }

    function formatProfileDateTime(ts) {
        if (!ts) return '';
        var d = new Date(ts);
        return d.toLocaleString();
    }

    function formatProfileBytes(bytes) {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / 1048576).toFixed(1) + ' MB';
    }

    function saveProfile() {
        captureCurrentStepState();
        var name = ($('#sigProfileName').val() || '').trim();
        if (!name) return;
        var profile = {
            name: name,
            visualMode: _wizardState.visualMode,
            displayName: _wizardState.displayName,
            fontName: _wizardState.fontName,
            imageDataBase64: _wizardState.imageDataBase64,
            drawnImageBase64: _wizardState.drawnImageBase64,
            drawSmoothAlgo: _wizardState.drawSmoothAlgo,
            drawSmoothing: _wizardState.drawSmoothing,
            drawPenWidth: _wizardState.drawPenWidth,
            biometricEnabled: _wizardState.biometricEnabled,
            bioFormat: _wizardState.bioFormat
        };

        // Compute a small thumbnail + image dimensions for the profile
        var imgSrc = profile.drawnImageBase64 || profile.imageDataBase64 || null;
        if (profile.visualMode === 'text') {
            // Generate thumbnail from font preview
            var previewEl = document.getElementById('sigFontPreview');
            if (previewEl) {
                try {
                    var thumbCanvas = document.createElement('canvas');
                    thumbCanvas.width = 200;
                    thumbCanvas.height = 60;
                    var tctx = thumbCanvas.getContext('2d');
                    tctx.fillStyle = '#fff';
                    tctx.fillRect(0, 0, 200, 60);
                    var fontEntry = SIGNING_FONTS.find(function (f) { return f.id === profile.fontName; });
                    var fontCss = fontEntry ? fontEntry.css : 'sans-serif';
                    tctx.font = '28px "' + fontCss + '"';
                    tctx.fillStyle = '#1a1a2e';
                    tctx.textAlign = 'center';
                    tctx.textBaseline = 'middle';
                    tctx.fillText(profile.displayName || 'Signature', 100, 30);
                    profile.thumbnailDataUrl = thumbCanvas.toDataURL('image/png', 0.7);
                    profile.thumbnailWidth = 200;
                    profile.thumbnailHeight = 60;
                } catch (ignored) { /* skip thumbnail */ }
            }
        } else if (imgSrc) {
            // Generate a thumbnail from the image/drawn data
            (function (src) {
                var img = new Image();
                img.onload = function () {
                    profile.imageWidth = img.naturalWidth;
                    profile.imageHeight = img.naturalHeight;
                    profile.imageSizeEstimate = Math.round(src.length * 0.75); // base64 ratio
                    try {
                        var tc = document.createElement('canvas');
                        var maxW = 200, maxH = 60;
                        var s = Math.min(maxW / img.naturalWidth, maxH / img.naturalHeight, 1);
                        tc.width = Math.round(img.naturalWidth * s);
                        tc.height = Math.round(img.naturalHeight * s);
                        var ctx2 = tc.getContext('2d');
                        ctx2.drawImage(img, 0, 0, tc.width, tc.height);
                        profile.thumbnailDataUrl = tc.toDataURL('image/png', 0.7);
                        profile.thumbnailWidth = tc.width;
                        profile.thumbnailHeight = tc.height;
                    } catch (ignored) { /* skip thumbnail */ }
                    doSave(profile);
                };
                img.src = src;
            })(imgSrc);
            return; // doSave will be called from img.onload
        }
        doSave(profile);
    }

    function doSave(profile) {
        P.SigningStore.save(profile).then(function () {
            $('#sigProfileName').val('');
            $('#sigSaveProfile').prop('disabled', true);
            P.Utils.toast('Profile saved', 'success');
            loadProfiles();
        });
    }

    // ── Summary ─────────────────────────────────────────────────────────

    function renderSummary() {
        var html = '';
        html += summaryRow('Field', _fieldName);
        html += summaryRow('Signature Type', _wizardState.signMode === 'certification' ? 'Certification' : 'Approval');
        if (_wizardState.signMode === 'certification') {
            var mdpLabels = { 1: 'No changes allowed', 2: 'Form fill + signing', 3: 'Form fill + signing + annotations' };
            html += summaryRow('DocMDP', mdpLabels[_wizardState.docMdpLevel] || '?');
        }
        if (_wizardState.signMode !== 'certification') {
            html += summaryRow('Visual Mode', _wizardState.visualMode);
            if (_wizardState.visualMode === 'text') {
                html += summaryRow('Display Name', _wizardState.displayName);
                var fontEntry = SIGNING_FONTS.find(function (f) { return f.id === _wizardState.fontName; });
                html += summaryRow('Font', fontEntry ? fontEntry.label : _wizardState.fontName);
            }
        }
        html += summaryRow('PAdES Profile', _wizardState.padesProfile);
        if (_wizardState.padesProfile === 'B-T' && _wizardState.tsaServerName) {
            html += summaryRow('TSA Server', _wizardState.tsaServerName);
        }
        if (_wizardState.reason) html += summaryRow('Reason', _wizardState.reason);
        if (_wizardState.location) html += summaryRow('Location', _wizardState.location);
        if (_wizardState.excludedFields.length) html += summaryRow('Unlocked Fields', _wizardState.excludedFields.length + ': ' + _wizardState.excludedFields.join(', '));
        if (_wizardState.cscCertMode === 'csc' && _wizardState.cscProviderName) {
            html += summaryRow('Certificate', 'CSC Remote (' + _wizardState.cscProviderName + ')');
            html += summaryRow('Credential', _wizardState.cscCredentialId || 'None');
        } else {
            html += summaryRow('Certificate', _wizardState.sessionKeyId ? _wizardState.sessionKeyId.substring(0, 8) + '...' : 'None');
        }
        if (_wizardState.biometricEnabled && _wizardState.biometricData) {
            var bd = _wizardState.biometricData;
            html += summaryRow('Biometric Data', bd.totalPoints + ' points / ' + bd.totalStrokes + ' strokes');
            var fmtLabels = { json: 'JSON (uncompressed)', 'json-zip': 'JSON (deflate)', binary: 'Binary (compact)' };
            html += summaryRow('Bio Storage', fmtLabels[_wizardState.bioFormat] || _wizardState.bioFormat);
        }
        $('#sigSummary').html(html);
    }

    function summaryRow(label, value) {
        return '<div class="signing-summary-row"><span class="signing-summary-label">' + P.Utils.escapeHtml(label) + '</span><span class="signing-summary-value">' + P.Utils.escapeHtml(value || '') + '</span></div>';
    }

    // ── Queue signature ─────────────────────────────────────────────────

    function queueSignature() {
        captureCurrentStepState();

        // CSC remote signing: sign immediately via CSC provider (not queued)
        if (_wizardState.cscCertMode === 'csc' && _wizardState.cscProviderId && _wizardState.cscCredentialId) {
            performCscSign();
            return;
        }

        var body = {
            fieldName: _fieldName,
            sessionKeyId: _wizardState.sessionKeyId,
            visualMode: _wizardState.visualMode,
            displayName: _wizardState.displayName,
            fontName: _wizardState.fontName,
            imageDataBase64: _wizardState.imageDataBase64,
            drawnImageBase64: _wizardState.drawnImageBase64,
            signMode: _wizardState.signMode,
            docMdpLevel: _wizardState.docMdpLevel,
            padesProfile: _wizardState.padesProfile,
            excludedFields: _wizardState.excludedFields,
            reason: _wizardState.reason,
            location: _wizardState.location,
            contactInfo: _wizardState.contactInfo,
            biometricData: _wizardState.biometricEnabled && _wizardState.biometricData ? JSON.stringify(_wizardState.biometricData) : null,
            biometricFormat: _wizardState.biometricEnabled ? (_wizardState.bioFormat || 'json-zip') : null,
            tsaServerId: _wizardState.tsaServerId,
            tsaUrl: _wizardState.tsaUrl
        };

        $.ajax({
            url: '/api/signing/' + P.state.sessionId + '/prepare-signature',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(body)
        }).done(function (data) {
            if (!P.state.pendingSignatures) P.state.pendingSignatures = [];
            P.state.pendingSignatures.push(data);

            _bsModal.hide();
            P.Utils.toast('Signature queued for "' + _fieldName + '". Click Save to apply.', 'success');

            // Invalidate signature data cache so tab re-fetches
            P.state.signatureData = null;
            if (P.state.currentTab === 'signatures' && P.SignaturesTab) {
                P.SignaturesTab.loadSignatures();
            }

            // Update save button + badge — this shows the Changes tab button automatically
            if (P.EditMode && P.EditMode.updateSaveButton) P.EditMode.updateSaveButton();
        }).fail(function (xhr) {
            P.Utils.toast(xhr.responseJSON ? xhr.responseJSON.error : 'Failed to prepare signature', 'danger');
        });
    }

    // ── CSC Remote Signing ─────────────────────────────────────────────

    var _cscProviders = [];

    function loadCscProviders() {
        $.get('/api/signing/csc/providers').done(function (providers) {
            _cscProviders = providers || [];
            renderCscProviderDropdown();
        }).fail(function () {
            _cscProviders = [];
            renderCscProviderDropdown();
        });
    }

    function renderCscProviderDropdown() {
        var $sel = $('#sigCscProvider');
        if (!$sel.length) return;

        var prevVal = _wizardState.cscProviderId || '';
        var html = '<option value="">-- Select CSC Provider --</option>';

        // Separate built-in and user-added
        var builtIn = [];
        var userAdded = [];
        for (var i = 0; i < _cscProviders.length; i++) {
            if (_cscProviders[i].builtIn) builtIn.push(_cscProviders[i]);
            else userAdded.push(_cscProviders[i]);
        }

        if (builtIn.length) {
            html += '<optgroup label="Built-in Providers">';
            for (var b = 0; b < builtIn.length; b++) {
                var p = builtIn[b];
                var sel = p.id === prevVal ? ' selected' : '';
                var statusCls = cscStatusClass(p.status);
                html += '<option value="' + P.Utils.escapeHtml(p.id) + '"' + sel + '>';
                html += cscStatusIcon(p.status) + ' ' + P.Utils.escapeHtml(p.name);
                if (p.headquarters) html += ' [' + P.Utils.escapeHtml(p.headquarters) + ']';
                html += '</option>';
            }
            html += '</optgroup>';
        }

        if (userAdded.length) {
            html += '<optgroup label="Custom Providers">';
            for (var u = 0; u < userAdded.length; u++) {
                var up = userAdded[u];
                var usel = up.id === prevVal ? ' selected' : '';
                html += '<option value="' + P.Utils.escapeHtml(up.id) + '"' + usel + '>';
                html += cscStatusIcon(up.status) + ' ' + P.Utils.escapeHtml(up.name);
                html += '</option>';
            }
            html += '</optgroup>';
        }

        $sel.html(html);

        // Restore previous selection state
        if (prevVal && _wizardState.cscConnected) {
            $sel.val(prevVal);
            showCscProviderInfo(prevVal);
            $('#sigCscConnectForm').show();
        }
    }

    function cscStatusIcon(status) {
        switch (status) {
            case 'connected': return '\u2705';
            case 'error':     return '\u26A0\uFE0F';
            default:          return '\u2B55';
        }
    }

    function cscStatusClass(status) {
        switch (status) {
            case 'connected': return 'csc-status-connected';
            case 'error':     return 'csc-status-error';
            default:          return 'csc-status-unknown';
        }
    }

    function showCscProviderInfo(providerId) {
        var $info = $('#sigCscProviderInfo');
        if (!$info.length) return;

        var prov = _cscProviders.find(function (p) { return p.id === providerId; });
        if (!prov) { $info.html(''); return; }

        var html = '<div class="csc-info-card">';
        html += '<div class="csc-info-header">';
        html += '<strong>' + P.Utils.escapeHtml(prov.name) + '</strong>';
        if (prov.headquarters) html += ' <span class="csc-info-hq">' + P.Utils.escapeHtml(prov.headquarters) + '</span>';
        html += '</div>';
        if (prov.description) html += '<div class="csc-info-desc">' + P.Utils.escapeHtml(prov.description) + '</div>';
        var details = [];
        if (prov.apiVersion) details.push('API: ' + prov.apiVersion);
        if (prov.scalLevels) details.push('SCAL: ' + prov.scalLevels);
        if (details.length) html += '<div class="csc-info-meta">' + details.join(' &middot; ') + '</div>';
        if (prov.docsUrl) html += '<div class="csc-info-meta"><a href="' + P.Utils.escapeHtml(prov.docsUrl) + '" target="_blank" rel="noopener"><i class="fas fa-external-link-alt me-1"></i>Documentation</a></div>';
        html += '</div>';
        $info.html(html);
    }

    function connectCscProvider() {
        var providerId = _wizardState.cscProviderId;
        if (!providerId) {
            P.Utils.toast('Select a CSC provider first', 'warning');
            return;
        }

        var username = $('#sigCscUsername').val() || '';
        var password = $('#sigCscPassword').val() || '';

        var $btn = $('#sigCscConnectBtn');
        $btn.prop('disabled', true).html('<i class="fas fa-spinner fa-spin me-1"></i>Connecting...');

        $.ajax({
            url: '/api/signing/csc/' + encodeURIComponent(providerId) + '/connect',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({
                sessionId: P.state.sessionId,
                username: username,
                password: password
            })
        }).done(function (result) {
            if (result.connected) {
                _wizardState.cscConnected = true;
                var prov = _cscProviders.find(function (p) { return p.id === providerId; });
                _wizardState.cscProviderName = prov ? prov.name : providerId;
                $('#sigCscStatus').html('<div class="csc-status-badge csc-status-connected"><i class="fas fa-check-circle me-1"></i>Connected</div>');
                P.Utils.toast('Connected to ' + _wizardState.cscProviderName, 'success');
                loadCscCredentials(providerId);
            } else {
                _wizardState.cscConnected = false;
                var errMsg = result.error || 'Connection failed';
                $('#sigCscStatus').html('<div class="csc-status-badge csc-status-error"><i class="fas fa-exclamation-triangle me-1"></i>' + P.Utils.escapeHtml(errMsg) + '</div>');
                P.Utils.toast(errMsg, 'danger');
            }
        }).fail(function (xhr) {
            _wizardState.cscConnected = false;
            var msg = xhr.responseJSON ? (xhr.responseJSON.error || 'Connection failed') : 'Connection failed';
            $('#sigCscStatus').html('<div class="csc-status-badge csc-status-error"><i class="fas fa-exclamation-triangle me-1"></i>' + P.Utils.escapeHtml(msg) + '</div>');
            P.Utils.toast(msg, 'danger');
        }).always(function () {
            $btn.prop('disabled', false).html('<i class="fas fa-plug me-1"></i>Connect');
        });
    }

    function loadCscCredentials(providerId) {
        var $list = $('#sigCscCredentialsList');
        $list.html('<div class="text-center"><i class="fas fa-spinner fa-spin"></i> Loading credentials...</div>');
        $('#sigCscCredentials').show();

        $.get('/api/signing/csc/' + encodeURIComponent(P.state.sessionId) + '/' + encodeURIComponent(providerId) + '/credentials')
            .done(function (credentials) {
                renderCscCredentials(credentials || []);
            }).fail(function (xhr) {
                var msg = xhr.responseJSON ? (xhr.responseJSON.error || 'Failed to load credentials') : 'Failed to load credentials';
                $list.html('<div class="text-muted text-center" style="font-size:11px;">' + P.Utils.escapeHtml(msg) + '</div>');
            });
    }

    function renderCscCredentials(credentials) {
        var $list = $('#sigCscCredentialsList');
        if (!credentials.length) {
            $list.html('<div class="text-muted text-center" style="font-size:11px;">No signing credentials found</div>');
            return;
        }

        var html = '';
        for (var i = 0; i < credentials.length; i++) {
            var c = credentials[i];
            var sel = c.credentialId === _wizardState.cscCredentialId ? ' selected' : '';
            var disabled = c.status === 'disabled';

            html += '<div class="csc-credential-item' + sel + (disabled ? ' disabled' : '') + '"';
            html += ' data-cred-id="' + P.Utils.escapeHtml(c.credentialId) + '"';
            html += ' data-cred-scal="' + P.Utils.escapeHtml(c.scal || '1') + '">';

            // Icon
            html += '<span class="csc-cred-icon"><i class="fas fa-id-card"></i></span>';

            // Info
            html += '<div class="csc-cred-info">';
            html += '<div class="csc-cred-subject">' + P.Utils.escapeHtml(formatDN(c.subjectDN) || c.credentialId) + '</div>';
            if (c.issuerDN) html += '<div class="csc-cred-issuer">Issued by: ' + P.Utils.escapeHtml(formatDN(c.issuerDN)) + '</div>';

            var meta = [];
            if (c.keyAlgorithm) meta.push(c.keyAlgorithm);
            if (c.keyLength) meta.push(c.keyLength + '-bit');
            if (c.scal) meta.push('SCAL' + c.scal);
            if (c.validFrom && c.validUntil) meta.push(c.validFrom + ' — ' + c.validUntil);
            if (meta.length) html += '<div class="csc-cred-meta">' + P.Utils.escapeHtml(meta.join(' \u00B7 ')) + '</div>';

            html += '</div>';

            // Status badge
            html += '<div class="csc-cred-status">';
            if (disabled) {
                html += '<span class="csc-cred-badge csc-cred-disabled"><i class="fas fa-ban me-1"></i>Disabled</span>';
            } else if (c.scal === '2') {
                html += '<span class="csc-cred-badge csc-cred-scal2"><i class="fas fa-shield-alt me-1"></i>SCAL2</span>';
            } else {
                html += '<span class="csc-cred-badge csc-cred-ready"><i class="fas fa-check me-1"></i>Ready</span>';
            }
            html += '</div>';

            html += '</div>';
        }
        $list.html(html);
    }

    function sendCscOtp() {
        var providerId = _wizardState.cscProviderId;
        var credentialId = _wizardState.cscCredentialId;
        if (!providerId || !credentialId) {
            P.Utils.toast('Select a credential first', 'warning');
            return;
        }

        var $btn = $('#sigCscSendOtp');
        $btn.prop('disabled', true).html('<i class="fas fa-spinner fa-spin me-1"></i>Sending...');

        $.ajax({
            url: '/api/signing/csc/' + encodeURIComponent(P.state.sessionId) + '/' + encodeURIComponent(providerId) + '/send-otp',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ credentialId: credentialId })
        }).done(function () {
            P.Utils.toast('OTP sent — check your device', 'success');
        }).fail(function (xhr) {
            var msg = xhr.responseJSON ? (xhr.responseJSON.error || 'Failed to send OTP') : 'Failed to send OTP';
            P.Utils.toast(msg, 'danger');
        }).always(function () {
            $btn.prop('disabled', false).html('<i class="fas fa-paper-plane me-1"></i>Send OTP');
        });
    }

    function performCscSign() {
        var providerId = _wizardState.cscProviderId;
        var credentialId = _wizardState.cscCredentialId;
        var otp = $('#sigCscOtp').val() || null;

        var $btn = _modal.find('#sigNextBtn');
        $btn.prop('disabled', true).html('<i class="fas fa-spinner fa-spin me-1"></i>Signing...');

        $.ajax({
            url: '/api/signing/csc/' + encodeURIComponent(P.state.sessionId) + '/' + encodeURIComponent(providerId) + '/sign',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({
                credentialId: credentialId,
                fieldName: _fieldName,
                otp: otp,
                reason: _wizardState.reason || null,
                location: _wizardState.location || null
            })
        }).done(function (result) {
            _bsModal.hide();
            P.Utils.toast('Field "' + _fieldName + '" signed via ' + (result.provider || 'CSC'), 'success');

            // Clear pending signatures and signature cache, then reload with returned tree
            P.state.pendingSignatures = [];
            P.state.signatureData = null;
            if (result.tree) {
                P.Utils.refreshAfterMutation(result.tree);
            }
            if (P.EditMode && P.EditMode.updateSaveButton) P.EditMode.updateSaveButton();
        }).fail(function (xhr) {
            var msg = xhr.responseJSON ? (xhr.responseJSON.error || 'CSC signing failed') : 'CSC signing failed';
            P.Utils.toast(msg, 'danger');
        }).always(function () {
            $btn.prop('disabled', false).html('<i class="fas fa-file-signature me-1"></i>Queue Signature');
        });
    }

    function showAddCscProviderDialog() {
        var name = prompt('Provider name:');
        if (!name || !name.trim()) return;
        var baseUrl = prompt('CSC API base URL (e.g. https://provider.example.com/csc/v1):');
        if (!baseUrl || !baseUrl.trim()) return;
        var tokenUrl = prompt('OAuth2 token URL (leave empty if same as base URL):');
        var clientId = prompt('Client ID (optional):');
        var clientSecret = prompt('Client Secret (optional):');

        $.ajax({
            url: '/api/signing/csc/provider',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({
                name: name.trim(),
                baseUrl: baseUrl.trim(),
                tokenUrl: (tokenUrl && tokenUrl.trim()) || null,
                clientId: (clientId && clientId.trim()) || null,
                clientSecret: (clientSecret && clientSecret.trim()) || null
            })
        }).done(function (prov) {
            P.Utils.toast('Provider "' + prov.name + '" added', 'success');
            loadCscProviders();
            // Auto-select the new provider
            setTimeout(function () {
                $('#sigCscProvider').val(prov.id).trigger('change');
            }, 200);
        }).fail(function (xhr) {
            var msg = xhr.responseJSON ? (xhr.responseJSON.error || 'Failed to add provider') : 'Failed to add provider';
            P.Utils.toast(msg, 'danger');
        });
    }

    // ── TSA Server Management ───────────────────────────────────────────

    var _tsaServers = [];
    var _tsaPollTimer = null;

    function loadTsaServers() {
        $.get('/api/signing/tsa/servers').done(function (servers) {
            _tsaServers = servers || [];
            renderTsaDropdown();
            // Poll for probe results until all are resolved
            startTsaPolling();
        }).fail(function () {
            _tsaServers = [];
            renderTsaDropdown();
        });
    }

    function startTsaPolling() {
        stopTsaPolling();
        _tsaPollTimer = setInterval(function () {
            var hasProbing = false;
            for (var i = 0; i < _tsaServers.length; i++) {
                if (_tsaServers[i].status === 'probing' || _tsaServers[i].status === 'unknown') {
                    hasProbing = true;
                    break;
                }
            }
            if (!hasProbing) {
                stopTsaPolling();
                return;
            }
            $.get('/api/signing/tsa/servers').done(function (servers) {
                _tsaServers = servers || [];
                renderTsaDropdown();
            });
        }, 3000);
    }

    function stopTsaPolling() {
        if (_tsaPollTimer) {
            clearInterval(_tsaPollTimer);
            _tsaPollTimer = null;
        }
    }

    function renderTsaDropdown() {
        var $sel = $('#sigTsaServer');
        if (!$sel.length) return;

        var prevVal = _wizardState.tsaServerId || $sel.val();
        var html = '<option value="">-- Select TSA Server --</option>';

        // Group by category
        var categories = [
            { key: 'qualified', label: 'Qualified (eIDAS)', icon: 'fa-shield-alt' },
            { key: 'commercial', label: 'Commercial (free tier)', icon: 'fa-building' },
            { key: 'government', label: 'Government / National', icon: 'fa-landmark' },
            { key: 'academic',   label: 'Academic / Research', icon: 'fa-graduation-cap' },
            { key: 'community',  label: 'Community / Open Source', icon: 'fa-users' }
        ];

        // Sort: online first, then by response time
        var sorted = _tsaServers.slice().sort(function (a, b) {
            var so = statusOrder(a.status) - statusOrder(b.status);
            if (so !== 0) return so;
            return (a.responseTimeMs || 99999) - (b.responseTimeMs || 99999);
        });

        for (var ci = 0; ci < categories.length; ci++) {
            var cat = categories[ci];
            var entries = sorted.filter(function (s) { return s.category === cat.key; });
            if (!entries.length) continue;

            html += '<optgroup label="' + cat.label + '">';
            for (var i = 0; i < entries.length; i++) {
                var s = entries[i];
                var icon = statusIcon(s.status);
                var timing = s.responseTimeMs ? ' (' + s.responseTimeMs + 'ms)' : '';
                var sel = (s.id === prevVal) ? ' selected' : '';
                var dis = (s.status === 'offline' || s.status === 'error') ? ' disabled' : '';
                html += '<option value="' + s.id + '"' + sel + dis;
                html += ' data-tsa-id="' + P.Utils.escapeHtml(s.id) + '"';
                html += ' data-tsa-url="' + P.Utils.escapeHtml(s.url) + '"';
                html += ' data-tsa-name="' + P.Utils.escapeHtml(s.name) + '"';
                html += '>' + icon + ' ' + P.Utils.escapeHtml(s.name);
                html += ' [' + P.Utils.escapeHtml(s.country || '??') + ']' + timing;
                html += '</option>';
            }
            html += '</optgroup>';
        }

        $sel.html(html);

        // Auto-select first online server if none selected
        if (!prevVal || !$sel.find('option[value="' + prevVal + '"]:not(:disabled)').length) {
            var firstOnline = sorted.find(function (s) { return s.status === 'online'; });
            if (firstOnline) {
                $sel.val(firstOnline.id);
                _wizardState.tsaServerId = firstOnline.id;
                _wizardState.tsaUrl = firstOnline.url;
                _wizardState.tsaServerName = firstOnline.name;
            }
        }

        // Update info panel
        updateTsaInfo();
    }

    function statusOrder(status) {
        switch (status) {
            case 'online': return 0;
            case 'probing': return 1;
            case 'unknown': return 2;
            case 'error': return 3;
            case 'offline': return 4;
            default: return 5;
        }
    }

    function statusIcon(status) {
        switch (status) {
            case 'online':  return '\u2705'; // green check
            case 'probing': return '\u23F3'; // hourglass
            case 'unknown': return '\u2754'; // question
            case 'error':   return '\u26A0\uFE0F'; // warning
            case 'offline': return '\u274C'; // red X
            default:        return '\u2753';
        }
    }

    function updateTsaInfo() {
        var $info = $('#sigTsaInfo');
        if (!$info.length) return;

        var selId = $('#sigTsaServer').val();
        if (!selId) {
            $info.html('');
            return;
        }

        var server = _tsaServers.find(function (s) { return s.id === selId; });
        if (!server) {
            $info.html('');
            return;
        }

        var html = '<div class="tsa-info-card">';
        html += '<div class="tsa-info-header">';
        html += '<span class="tsa-status-badge tsa-status-' + server.status + '">' + server.status.toUpperCase() + '</span>';
        if (server.qualifiedEidas) html += '<span class="tsa-badge-qualified"><i class="fas fa-shield-alt me-1"></i>eIDAS Qualified</span>';
        if (server.responseTimeMs) html += '<span class="tsa-info-timing"><i class="fas fa-tachometer-alt me-1"></i>' + server.responseTimeMs + 'ms</span>';
        html += '</div>';
        html += '<div class="tsa-info-row"><strong>Provider:</strong> ' + P.Utils.escapeHtml(server.provider || '') + '</div>';
        html += '<div class="tsa-info-row"><strong>Country:</strong> ' + P.Utils.escapeHtml(server.country || '') + '</div>';
        html += '<div class="tsa-info-row"><strong>URL:</strong> <code>' + P.Utils.escapeHtml(server.url || '') + '</code></div>';
        if (server.info) html += '<div class="tsa-info-desc">' + P.Utils.escapeHtml(server.info) + '</div>';
        if (server.lastProbeError) html += '<div class="tsa-info-error"><i class="fas fa-exclamation-triangle me-1"></i>' + P.Utils.escapeHtml(server.lastProbeError) + '</div>';
        html += '</div>';
        $info.html(html);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    function formatDN(dn) {
        if (!dn) return '';
        var match = dn.match(/CN=([^,]+)/i);
        return match ? match[1].trim() : dn.replace(/,/g, ', ');
    }

    return { openWizard: openWizard };
})(jQuery, PDFalyzer);

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
            biometricEnabled: true,
            bioFormat: 'json-zip',
            biometricData: null
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
        html += '<div class="signing-visual-tabs">';
        html += '<div class="signing-visual-tab' + (_wizardState.visualMode === 'text' ? ' active' : '') + '" data-vmode="text"><i class="fas fa-font"></i>Name &amp; Font</div>';
        html += '<div class="signing-visual-tab' + (_wizardState.visualMode === 'image' ? ' active' : '') + '" data-vmode="image"><i class="fas fa-image"></i>Image</div>';
        html += '<div class="signing-visual-tab' + (_wizardState.visualMode === 'draw' ? ' active' : '') + '" data-vmode="draw"><i class="fas fa-pen"></i>Draw</div>';
        html += '<div class="signing-visual-tab' + (_wizardState.visualMode === 'invisible' ? ' active' : '') + '" data-vmode="invisible"><i class="fas fa-eye-slash"></i>Invisible</div>';
        html += '</div>';

        // Text mode
        html += '<div class="signing-visual-content' + (_wizardState.visualMode === 'text' ? ' active' : '') + '" data-vcontent="text">';
        html += '<div class="mb-2"><label class="form-label">Display Name</label>';
        html += '<input type="text" class="form-control form-control-sm" id="sigDisplayName" placeholder="e.g. John Doe" value="' + P.Utils.escapeHtml(_wizardState.displayName || '') + '"></div>';
        html += '<div class="mb-2"><label class="form-label">Font</label>';
        html += '<div class="signing-font-preview" id="sigFontPreview" style="font-family:\'' + (_wizardState.fontName || 'Dancing Script') + '\'">' + P.Utils.escapeHtml(_wizardState.displayName || 'Your Name') + '</div>';
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
        html += '<label class="signing-tb-label" title="Bezier curve smoothing intensity">Smooth</label>';
        html += '<input type="range" class="signing-tb-slider" id="sigSmoothing" min="0" max="1" step="0.1" value="' + (_wizardState.drawSmoothing != null ? _wizardState.drawSmoothing : 0.5) + '">';
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

        html += '</div>';
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
        html += '<div class="row g-2">';
        html += '<div class="col-6"><label class="form-label">Common Name *</label><input type="text" class="form-control form-control-sm" id="sigSsCn" placeholder="Your Name"></div>';
        html += '<div class="col-6"><label class="form-label">Organization</label><input type="text" class="form-control form-control-sm" id="sigSsOrg" placeholder="Company"></div>';
        html += '</div>';
        html += '<div class="row g-2 mt-1">';
        html += '<div class="col-4"><label class="form-label">Country</label><input type="text" class="form-control form-control-sm" id="sigSsCountry" placeholder="CH" maxlength="2"></div>';
        html += '<div class="col-4"><label class="form-label">Algorithm</label><select class="form-select form-select-sm" id="sigSsAlgo"><option value="RSA">RSA 2048</option><option value="EC">EC P-256</option></select></div>';
        html += '<div class="col-4"><label class="form-label">Validity (days)</label><input type="number" class="form-control form-control-sm" id="sigSsDays" value="365" min="1" max="3650"></div>';
        html += '</div>';
        html += '<button class="btn btn-outline-accent btn-sm mt-2" id="sigGenerateSs"><i class="fas fa-wand-magic-sparkles me-1"></i>Generate</button>';
        html += '</div>';

        html += '<div class="signing-info-tip mt-2"><i class="fas fa-info-circle me-1"></i>Private keys are held <strong>server-side in memory only</strong> for the duration of the session. They are never stored permanently or sent to the browser.</div>';

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
        html += '<option value="B-T"' + (_wizardState.padesProfile === 'B-T' ? ' selected' : '') + ' disabled>PAdES Baseline B-T (requires TSA)</option>';
        html += '</select>';
        html += '<div class="signing-info-tip mt-1"><i class="fas fa-info-circle me-1"></i><strong>B-B</strong> includes the signing certificate. <strong>B-T</strong> adds a trusted timestamp (not yet supported).</div>';
        html += '</div>';

        // Reason / location / contact
        html += '<div class="signing-option-group"><label>Metadata (optional)</label>';
        html += '<div class="row g-2">';
        html += '<div class="col-12"><input type="text" class="form-control form-control-sm" id="sigReason" placeholder="Reason for signing" value="' + P.Utils.escapeHtml(_wizardState.reason || '') + '"></div>';
        html += '<div class="col-6"><input type="text" class="form-control form-control-sm" id="sigLocation" placeholder="Location" value="' + P.Utils.escapeHtml(_wizardState.location || '') + '"></div>';
        html += '<div class="col-6"><input type="text" class="form-control form-control-sm" id="sigContact" placeholder="Contact info" value="' + P.Utils.escapeHtml(_wizardState.contactInfo || '') + '"></div>';
        html += '</div></div>';

        // Excluded fields
        html += '<div class="signing-option-group"><label>Excluded Fields</label>';
        html += '<div class="signing-info-tip mb-1"><i class="fas fa-info-circle me-1"></i>Select form fields whose values should NOT be covered by this signature. Excluded fields can be modified after signing without invalidating it.</div>';
        html += '<div class="signing-excluded-fields" id="sigExcludedFields"><div class="text-muted" style="font-size:11px;">Loading form fields...</div></div>';
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
        if (step === 'certificate') refreshKeys();
        if (step === 'options') loadExcludedFieldsList();
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
            _wizardState.excludedFields = [];
            $('#sigExcludedFields input:checked').each(function () {
                _wizardState.excludedFields.push($(this).val());
            });
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
                P.Utils.toast('Please select or upload a certificate', 'warning');
                return false;
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

        // Sign mode toggle DocMDP visibility + update steps
        _modal.on('change.sigwiz', 'input[name="sigSignMode"]', function () {
            var isCert = $(this).val() === 'certification';
            _wizardState.signMode = isCert ? 'certification' : 'approval';
            if (isCert) _wizardState.visualMode = 'invisible';
            $('#sigDocMdpGroup').toggle(isCert);
            // Rebuild step indicator to reflect changed steps
            _modal.find('.signing-steps').replaceWith(buildStepIndicator());
        });

        // Profile save
        _modal.on('input.sigwiz', '#sigProfileName', function () { $('#sigSaveProfile').prop('disabled', !$(this).val().trim()); });
        _modal.on('click.sigwiz', '#sigSaveProfile', saveProfile);

        // Key selection
        _modal.on('click.sigwiz', '.signing-key-item', function () {
            var keyId = $(this).data('key-id');
            _wizardState.sessionKeyId = keyId;
            _modal.find('.signing-key-item').removeClass('selected');
            $(this).addClass('selected');
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

    function generateSelfSigned() {
        var cn = $('#sigSsCn').val();
        if (!cn || !cn.trim()) {
            P.Utils.toast('Common Name is required', 'warning');
            return;
        }
        var algoSel = $('#sigSsAlgo').val();
        var body = {
            commonName: cn.trim(),
            organization: $('#sigSsOrg').val() || null,
            country: $('#sigSsCountry').val() || null,
            validityDays: parseInt($('#sigSsDays').val(), 10) || 365,
            keyAlgorithm: algoSel === 'EC' ? 'EC' : 'RSA',
            keySize: algoSel === 'EC' ? 256 : 2048
        };

        P.Utils.toast('Generating certificate...', 'info');
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
            html += '<div style="font-size:10px;color:' + (k.readyToSign ? 'var(--c-ok)' : 'var(--c-err)') + ';">';
            html += k.readyToSign ? '<i class="fas fa-check-circle me-1"></i>Ready to sign' : '<i class="fas fa-exclamation-circle me-1"></i>' + (k.missingElements || []).join(', ');
            html += '</div></div>';
            html += '<div class="key-actions">';
            html += '<button class="btn btn-outline-secondary btn-sm signing-key-export" title="Download as PKCS12"><i class="fas fa-download"></i></button>';
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
            P.SigningCanvas.setSmoothingLevel(_wizardState.drawSmoothing != null ? _wizardState.drawSmoothing : 0.5);
            P.SigningCanvas.setBiometricEnabled(_wizardState.biometricEnabled !== false);
            if (_wizardState.drawnImageBase64 && P.SigningCanvas.loadImage) {
                P.SigningCanvas.loadImage(_wizardState.drawnImageBase64);
            }
        }
    }

    // ── Excluded fields ─────────────────────────────────────────────────

    function loadExcludedFieldsList() {
        var $container = $('#sigExcludedFields');
        if (!$container.length) return;

        var fields = collectFormFieldNames();
        if (!fields.length) {
            $container.html('<div class="text-muted" style="font-size:11px;">No form fields found</div>');
            return;
        }

        var html = '';
        for (var i = 0; i < fields.length; i++) {
            var checked = _wizardState.excludedFields.indexOf(fields[i]) >= 0 ? ' checked' : '';
            html += '<label><input type="checkbox" value="' + P.Utils.escapeHtml(fields[i]) + '"' + checked + '> ' + P.Utils.escapeHtml(fields[i]) + '</label>';
        }
        $container.html(html);
    }

    function collectFormFieldNames() {
        var names = [];
        if (!P.state.treeData) return names;
        walkTree(P.state.treeData, function (node) {
            if (node.category === 'formfield' && node.name && node.name !== _fieldName) {
                names.push(node.name);
            }
        });
        return names;
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
                // Thumbnail
                if (p.thumbnailDataUrl) {
                    html += '<img class="profile-thumb" src="' + p.thumbnailDataUrl + '" alt="">';
                } else {
                    html += '<span class="profile-thumb-placeholder"><i class="fas ' + profileModeIcon(p.visualMode) + '"></i></span>';
                }
                // Info column
                html += '<div class="profile-info">';
                html += '<span class="profile-name">' + P.Utils.escapeHtml(p.name || 'Profile ' + p.id) + '</span>';
                html += '<span class="profile-meta">' + profileModeLabel(p.visualMode);
                if (p.visualMode === 'text') {
                    var fe = SIGNING_FONTS.find(function (f) { return f.id === p.fontName; });
                    if (fe) html += ' &middot; ' + P.Utils.escapeHtml(fe.label);
                }
                html += '</span>';
                html += '<span class="profile-meta">';
                if (p.createdAt) html += 'Created ' + formatProfileDate(p.createdAt);
                if (p.lastUsedAt) html += ' &middot; Used ' + formatProfileDate(p.lastUsedAt);
                if (p.useCount) html += ' (' + p.useCount + 'x)';
                html += '</span>';
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
        var html = '<div class="sig-tooltip-inner">';
        if (prof.thumbnailDataUrl) {
            html += '<img class="sig-tooltip-img" src="' + prof.thumbnailDataUrl + '" alt="">';
        }
        html += '<table class="sig-tooltip-table">';
        html += '<tr><td>Name</td><td>' + P.Utils.escapeHtml(prof.name || '') + '</td></tr>';
        html += '<tr><td>Mode</td><td>' + profileModeLabel(prof.visualMode) + '</td></tr>';
        if (prof.displayName) html += '<tr><td>Display</td><td>' + P.Utils.escapeHtml(prof.displayName) + '</td></tr>';
        if (prof.fontName) {
            var fe2 = SIGNING_FONTS.find(function (f) { return f.id === prof.fontName; });
            html += '<tr><td>Font</td><td>' + P.Utils.escapeHtml(fe2 ? fe2.label : prof.fontName) + '</td></tr>';
        }
        if (prof.imageWidth && prof.imageHeight) {
            html += '<tr><td>Dimensions</td><td>' + prof.imageWidth + ' x ' + prof.imageHeight + ' px</td></tr>';
        }
        if (prof.imageSizeEstimate) {
            html += '<tr><td>Size</td><td>~' + formatProfileBytes(prof.imageSizeEstimate) + '</td></tr>';
        }
        if (prof.createdAt) html += '<tr><td>Created</td><td>' + formatProfileDateTime(prof.createdAt) + '</td></tr>';
        if (prof.lastUsedAt) html += '<tr><td>Last used</td><td>' + formatProfileDateTime(prof.lastUsedAt) + '</td></tr>';
        if (prof.useCount) html += '<tr><td>Times used</td><td>' + prof.useCount + '</td></tr>';
        html += '</table></div>';
        $tip.html(html);

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
            drawnImageBase64: _wizardState.drawnImageBase64
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
        if (_wizardState.reason) html += summaryRow('Reason', _wizardState.reason);
        if (_wizardState.location) html += summaryRow('Location', _wizardState.location);
        if (_wizardState.excludedFields.length) html += summaryRow('Excluded Fields', _wizardState.excludedFields.join(', '));
        html += summaryRow('Certificate', _wizardState.sessionKeyId ? _wizardState.sessionKeyId.substring(0, 8) + '...' : 'None');
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
            biometricFormat: _wizardState.biometricEnabled ? (_wizardState.bioFormat || 'json-zip') : null
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

    // ── Helpers ──────────────────────────────────────────────────────────

    function formatDN(dn) {
        if (!dn) return '';
        var match = dn.match(/CN=([^,]+)/i);
        return match ? match[1].trim() : dn.replace(/,/g, ', ');
    }

    return { openWizard: openWizard };
})(jQuery, PDFalyzer);

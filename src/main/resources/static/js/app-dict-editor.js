/**
 * PDFalyzer Studio – dictionary type metadata and entry-add validation.
 */
PDFalyzer.DictEditor = (function () {
    'use strict';

    var dictTypeInfo = {
        'Catalog':  { description: 'PDF Document Catalog (root)',
                      required: ['Type', 'Pages'],
                      common:   ['Pages', 'Outlines', 'StructTreeRoot', 'Lang', 'AcroForm'] },
        'Pages':    { description: 'Pages tree node',
                      required: ['Type', 'Count', 'Kids'],
                      common:   ['Kids', 'Count', 'Parent', 'MediaBox', 'Resources'] },
        'Page':     { description: 'Individual page',
                      required: ['Type', 'Parent', 'MediaBox'],
                      common:   ['MediaBox', 'Contents', 'Resources', 'Parent', 'Rotate',
                                 'CropBox', 'BleedBox', 'TrimBox', 'ArtBox'] },
        'AcroForm': { description: 'Interactive form (AcroForm)',
                      required: ['Fields'],
                      common:   ['Fields', 'SigFlags', 'NeedAppearances', 'DA', 'Resources'] },
        'Font':     { description: 'Font descriptor',
                      required: ['Type', 'Subtype', 'BaseFont'],
                      common:   ['BaseFont', 'Subtype', 'Encoding', 'FirstChar',
                                 'LastChar', 'Widths', 'FontDescriptor', 'ToUnicode'] },
        'XObject':  { description: 'External object (image, form, etc.)',
                      required: ['Type', 'Subtype'],
                      common:   ['Subtype', 'Width', 'Height', 'ColorSpace', 'BitsPerComponent', 'Filter'] },
        'GenericDictionary': { description: 'Generic PDF Dictionary', common: [] }
    };

    function detectDictType(node) {
        if (!node.properties) return 'GenericDictionary';
        var type = node.properties.Type;
        if (type === 'Catalog')  return 'Catalog';
        if (type === 'Pages')    return 'Pages';
        if (type === 'Page')     return 'Page';
        if (type === 'Font')     return 'Font';
        if (type === 'XObject')  return 'XObject';
        if (node.name === '/AcroForm' || type === 'AcroForm') return 'AcroForm';
        return 'GenericDictionary';
    }

    function getDictTypeInfo(dictType) {
        return dictTypeInfo[dictType] || dictTypeInfo['GenericDictionary'];
    }

    function validateAddEntry(parentNode, newKey) {
        if (!newKey || newKey.trim().length === 0)
            return { valid: false, message: 'Key cannot be empty' };
        if (parentNode.children) {
            var exists = parentNode.children.some(function (child) {
                return child.name === '/' + newKey || child.name === newKey;
            });
            if (exists) return { valid: false, message: 'Key "' + newKey + '" already exists' };
        }
        return { valid: true, message: 'Key is valid' };
    }

    return { detectDictType: detectDictType, getDictTypeInfo: getDictTypeInfo,
             validateAddEntry: validateAddEntry };
}());

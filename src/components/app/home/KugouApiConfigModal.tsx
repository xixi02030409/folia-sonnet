import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { X } from 'lucide-react';

// src/components/app/home/KugouApiConfigModal.tsx

type KugouApiConfigModalProps = {
    title: string;
    description: string;
    placeholder: string;
    saveLabel: string;
    invalidLabel: string;
    closeLabel: string;
    initialValue?: string;
    onSave: (apiBase: string) => void;
    onClose: () => void;
};

const normalizeInput = (value: string): string => value.trim().replace(/\/+$/, '');

const isValidApiBase = (value: string): boolean => {
    if (!value) return false;
    try {
        const parsed = new URL(value);
        return parsed.protocol === 'http:' || parsed.protocol === 'https:';
    } catch {
        return false;
    }
};

const KugouApiConfigModal = ({
    title,
    description,
    placeholder,
    saveLabel,
    invalidLabel,
    closeLabel,
    initialValue = '',
    onSave,
    onClose,
}: KugouApiConfigModalProps) => {
    const [value, setValue] = useState(initialValue);
    const [showInvalid, setShowInvalid] = useState(false);

    useEffect(() => {
        setValue(initialValue);
    }, [initialValue]);

    const handleSave = () => {
        const normalized = normalizeInput(value);
        if (!isValidApiBase(normalized)) {
            setShowInvalid(true);
            return;
        }
        onSave(normalized);
    };

    return (
        <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.25 }}
            className="absolute inset-0 z-[100] flex items-center justify-center bg-black/60 backdrop-blur-xl p-4"
            role="dialog"
            aria-modal="true"
            aria-label={title}
        >
            <motion.div
                initial={{ scale: 0.92, opacity: 0, y: 24 }}
                animate={{ scale: 1, opacity: 1, y: 0 }}
                exit={{ scale: 0.92, opacity: 0, y: 12 }}
                transition={{ type: 'spring', bounce: 0, duration: 0.5 }}
                className="bg-zinc-900/90 border border-white/10 p-8 rounded-3xl max-w-sm w-full text-left relative shadow-2xl"
            >
                <button
                    type="button"
                    onClick={onClose}
                    aria-label={closeLabel}
                    className="absolute top-4 right-4 opacity-40 hover:opacity-100 rounded-full bg-white/5 p-1 transition-colors cursor-pointer"
                    style={{ color: 'var(--text-primary)' }}
                >
                    <X size={16} />
                </button>
                <h3 className="text-lg font-bold mb-3" style={{ color: 'var(--text-primary)' }}>{title}</h3>
                <p className="text-xs opacity-60 mb-4 leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{description}</p>
                <input
                    type="url"
                    inputMode="url"
                    autoCapitalize="none"
                    autoCorrect="off"
                    spellCheck={false}
                    value={value}
                    placeholder={placeholder}
                    onChange={(event) => {
                        setValue(event.target.value);
                        setShowInvalid(false);
                    }}
                    onKeyDown={(event) => {
                        if (event.key === 'Enter') handleSave();
                    }}
                    className="w-full px-4 py-3 rounded-xl bg-white/5 border border-white/10 text-sm outline-none focus:border-white/25 transition-colors"
                    style={{ color: 'var(--text-primary)' }}
                />
                {showInvalid && (
                    <p className="text-xs text-red-400 mt-2">{invalidLabel}</p>
                )}
                <button
                    type="button"
                    onClick={handleSave}
                    className="w-full mt-4 px-4 py-3 rounded-xl bg-white/10 hover:bg-white/15 text-sm font-semibold transition-colors cursor-pointer"
                    style={{ color: 'var(--text-primary)' }}
                >
                    {saveLabel}
                </button>
            </motion.div>
        </motion.div>
    );
};

export default KugouApiConfigModal;

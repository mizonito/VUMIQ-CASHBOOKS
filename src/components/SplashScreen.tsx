import React, { useEffect, useState } from 'react';
import { LocaleCode } from '../types/cashbook';
import { translate } from '../i18n/translations';

interface SplashScreenProps {
  locale: LocaleCode;
  onComplete: () => void;
}

/**
 * 2026-Standard Premium Application Launch Experience for VUMIQ Cashbook
 * Branding Hierarchy: VUMIQ -> Cashbook -> Footer: OWNED & CREATED BY -> Mr. Amin M. Gutale
 * Compositor-only animations (opacity, transform), reduced-motion compliant, ~2.4s duration.
 */
export const SplashScreen: React.FC<SplashScreenProps> = ({ locale, onComplete }) => {
  const [stage, setStage] = useState<0 | 1 | 2 | 3 | 4>(0);

  useEffect(() => {
    const prefersReducedMotion =
      typeof window !== 'undefined' &&
      window.matchMedia &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    if (prefersReducedMotion) {
      setStage(3);
      const tDone = window.setTimeout(() => {
        setStage(4);
        window.setTimeout(onComplete, 120);
      }, 900);
      return () => window.clearTimeout(tDone);
    }

    const t1 = window.setTimeout(() => setStage(1), 120); // VUMIQ wordmark
    const t2 = window.setTimeout(() => setStage(2), 540); // Cashbook subtitle
    const t3 = window.setTimeout(() => setStage(3), 980); // OWNED & CREATED BY Mr. Amin M. Gutale
    const t4 = window.setTimeout(() => setStage(4), 2350); // Smooth fade out
    const t5 = window.setTimeout(() => onComplete(), 2650); // Enter main app

    return () => {
      window.clearTimeout(t1);
      window.clearTimeout(t2);
      window.clearTimeout(t3);
      window.clearTimeout(t4);
      window.clearTimeout(t5);
    };
  }, [onComplete]);

  return (
    <div
      role="region"
      aria-label="VUMIQ Cashbook Launch Introduction"
      className={`fixed inset-0 z-50 flex flex-col justify-between bg-[#08120E] text-white select-none overflow-hidden transition-opacity duration-300 ${
        stage === 4 ? 'opacity-0 pointer-events-none' : 'opacity-100'
      }`}
    >
      {/* Restrained radial emerald ambient illumination (zero cheap neon) */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0"
        style={{
          background:
            'radial-gradient(circle at 50% 44%, rgba(18, 107, 69, 0.22) 0%, rgba(8, 18, 14, 0) 62%)',
        }}
      />

      {/* Top spacer to optically center the focal brand composition */}
      <div className="h-16" aria-hidden="true" />

      {/* Center Focal Point: VUMIQ -> Cashbook */}
      <div className="relative z-10 flex flex-col items-center justify-center px-6 text-center">
        {/* Precision Geometric Crest */}
        <div
          className={`mb-5 transition-all duration-500 ease-out ${
            stage >= 1 ? 'opacity-100 scale-100 translate-y-0' : 'opacity-0 scale-95 translate-y-2'
          }`}
        >
          <svg
            width="52"
            height="52"
            viewBox="0 0 52 52"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
            aria-hidden="true"
          >
            <rect
              x="1"
              y="1"
              width="50"
              height="50"
              rx="14"
              fill="#0E241A"
              stroke="#20A56B"
              strokeOpacity="0.45"
              strokeWidth="1.25"
            />
            <path
              d="M15.5 17L26 36.5L36.5 17"
              stroke="#F4F8F6"
              strokeWidth="3.8"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
            <path
              d="M22 17L26 24.5L30 17"
              stroke="#34D399"
              strokeWidth="2.6"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </div>

        {/* Main Brand Wordmark with subtle metallic light sweep */}
        <div
          className={`relative overflow-hidden px-4 py-1 transition-all duration-500 ease-out ${
            stage >= 1 ? 'opacity-100 scale-100' : 'opacity-0 scale-[0.96]'
          }`}
        >
          <h1 className="font-display text-4xl sm:text-5xl font-bold tracking-[0.18em] text-[#F6FAF8] pl-[0.18em]">
            VUMIQ
          </h1>
          <div
            aria-hidden="true"
            className="vumiq-sweep-bar pointer-events-none absolute inset-y-0 w-20 bg-gradient-to-r from-transparent via-emerald-200/25 to-transparent"
          />
        </div>

        {/* Subtitle: Cashbook */}
        <p
          className={`mt-2 font-display text-base sm:text-lg font-medium tracking-[0.22em] text-[#9DC3B0] pl-[0.22em] transition-all duration-500 ease-out ${
            stage >= 2 ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-2'
          }`}
        >
          {translate(locale, 'brandSubtitle')}
        </p>
      </div>

      {/* Footer Integrated Permanent Brand Credit: OWNED & CREATED BY -> Mr. Amin M. Gutale */}
      <footer
        className={`relative z-10 pb-10 pt-6 px-6 text-center transition-all duration-500 ease-out ${
          stage >= 3 ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-2'
        }`}
      >
        <div className="mx-auto mb-4 h-px w-12 bg-[#20A56B]/35" aria-hidden="true" />
        <p className="text-[10px] sm:text-[11px] font-medium tracking-[0.24em] text-[#7D9B8B]">
          {translate(locale, 'ownedAndCreatedBy')}
        </p>
        <p className="mt-1.5 font-display text-sm sm:text-base font-semibold tracking-[0.04em] text-[#E6F1EC]">
          Mr. Amin M. Gutale
        </p>
      </footer>
    </div>
  );
};

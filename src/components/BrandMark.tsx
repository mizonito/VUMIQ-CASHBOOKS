import React from 'react';

interface BrandMarkProps {
  size?: 'sm' | 'md' | 'lg';
  showSubtitle?: boolean;
  subtitleText?: string;
  forceLightOnDark?: boolean;
}

/**
 * Original minimalist VUMIQ geometric emblem and wordmark.
 * Legible at small sizes (20px) and engineered for both light and dark surfaces.
 */
export const BrandMark: React.FC<BrandMarkProps> = ({
  size = 'md',
  showSubtitle = false,
  subtitleText = 'Cashbook',
  forceLightOnDark = false,
}) => {
  const iconDim = size === 'sm' ? 24 : size === 'lg' ? 40 : 30;
  const wordSize =
    size === 'sm' ? 'text-base' : size === 'lg' ? 'text-2xl' : 'text-lg';

  return (
    <div className="inline-flex items-center gap-2.5 select-none shrink-0 whitespace-nowrap">
      <svg
        width={iconDim}
        height={iconDim}
        viewBox="0 0 36 36"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
        aria-hidden="true"
        className="shrink-0"
      >
        <rect
          width="36"
          height="36"
          rx="9"
          fill={forceLightOnDark ? '#126B45' : 'currentColor'}
          className={forceLightOnDark ? '' : 'text-[#126B45] dark:text-[#198758]'}
        />
        {/* Minimalist precision V-chevron ledger mark */}
        <path
          d="M10.5 11.5L18 25.5L25.5 11.5"
          stroke="#FFFFFF"
          strokeWidth="3.1"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <path
          d="M15.25 11.5L18 16.75L20.75 11.5"
          stroke="#8CE8BE"
          strokeWidth="2.1"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>

      {showSubtitle ? (
        <div className="flex flex-col leading-none">
          <span
            className={`font-display font-bold tracking-tight ${wordSize} ${
              forceLightOnDark ? 'text-white' : 'text-[#17231D] dark:text-[#ECF3EE]'
            }`}
          >
            VUMIQ
          </span>
          <span
            className={`text-[11px] font-medium tracking-wide mt-0.5 ${
              forceLightOnDark ? 'text-[#A8C9B8]' : 'text-[#58655D] dark:text-[#98A89E]'
            }`}
          >
            {subtitleText}
          </span>
        </div>
      ) : (
        <span
          className={`font-display font-bold tracking-tight ${wordSize} ${
            forceLightOnDark ? 'text-white' : 'text-[#17231D] dark:text-[#ECF3EE]'
          }`}
        >
          VUMIQ <span className="font-medium opacity-80">{subtitleText}</span>
        </span>
      )}
    </div>
  );
};

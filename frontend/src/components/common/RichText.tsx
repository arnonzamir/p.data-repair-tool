import React from 'react';

/**
 * Renders text with basic markdown-like formatting:
 * - **bold** becomes <strong>
 * - `code` becomes <code>
 * - \n becomes <br/>
 * - IMPORTANT: / WARNING: at line start get auto-bolded
 */
export function renderRichText(text: string): React.ReactNode {
  // Split on newlines first, render each line
  const lines = text.split('\n');
  if (lines.length === 1) {
    return renderInline(text);
  }

  return (
    <>
      {lines.map((line, i) => (
        <React.Fragment key={i}>
          {i > 0 && <br />}
          {renderInline(line)}
        </React.Fragment>
      ))}
    </>
  );
}

function renderInline(text: string): React.ReactNode {
  // Auto-bold IMPORTANT: and WARNING: prefixes
  let processed = text;
  if (processed.startsWith('IMPORTANT:')) {
    processed = '**IMPORTANT:**' + processed.substring('IMPORTANT:'.length);
  } else if (processed.startsWith('WARNING:')) {
    processed = '**WARNING:**' + processed.substring('WARNING:'.length);
  }

  // Split on **bold** and `code` markers
  // Pattern: match **bold** or `code` segments
  const parts = processed.split(/(\*\*.+?\*\*|`.+?`)/g);
  if (parts.length === 1 && !processed.includes('**') && !processed.includes('`')) {
    return processed;
  }

  return (
    <>
      {parts.map((part, i) => {
        if (part.startsWith('**') && part.endsWith('**')) {
          return <strong key={i}>{part.slice(2, -2)}</strong>;
        }
        if (part.startsWith('`') && part.endsWith('`')) {
          return <code key={i} style={{ background: '#f5f5f5', padding: '1px 4px', borderRadius: 2, fontSize: '0.9em' }}>{part.slice(1, -1)}</code>;
        }
        return <React.Fragment key={i}>{part}</React.Fragment>;
      })}
    </>
  );
}

/**
 * Renders a block of markdown-like text as paragraphs.
 * Double newlines become paragraph breaks, single newlines become <br/>.
 */
export function renderMarkdownBlock(text: string): React.ReactNode {
  const paragraphs = text.split(/\n\n+/);
  return (
    <>
      {paragraphs.map((para, i) => (
        <p key={i} style={{ margin: '0 0 6px 0' }}>
          {renderRichText(para.trim())}
        </p>
      ))}
    </>
  );
}

interface RichTextProps {
  text: string;
  style?: React.CSSProperties;
  className?: string;
}

const RichText: React.FC<RichTextProps> = ({ text, style, className }) => (
  <span style={style} className={className}>{renderRichText(text)}</span>
);

export default RichText;

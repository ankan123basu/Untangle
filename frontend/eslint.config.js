// @ts-check
import js from '@eslint/js';
import prettier from 'eslint-config-prettier';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import globals from 'globals';
import tseslint from 'typescript-eslint';

/** Rules that apply to every TypeScript file, first-party or vendored. */
const projectRules = {
  // Project rule (Section 12.6): no `any`, ever.
  '@typescript-eslint/no-explicit-any': 'error',
  // Section 5.3: user content is rendered as plain text, never as HTML.
  'no-restricted-syntax': [
    'error',
    {
      selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
      message: 'Never render HTML from data. Render user content as plain text.',
    },
  ],
};

const VENDORED = 'src/components/reactbits/**/*.{ts,tsx}';

export default tseslint.config(
  {
    ignores: [
      'dist',
      'coverage',
      'playwright-report',
      'test-results',
      'src/lib/api/schema.d.ts',
      'public/mockServiceWorker.js',
    ],
  },
  // First-party code: the strictest type-aware rules.
  {
    files: ['**/*.{ts,tsx}'],
    ignores: [VENDORED],
    extends: [js.configs.recommended, ...tseslint.configs.strictTypeChecked],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      '@typescript-eslint/consistent-type-imports': 'error',
      // Numbers and booleans in template literals are fine and very common in UI copy.
      '@typescript-eslint/restrict-template-expressions': [
        'error',
        { allowNumber: true, allowBoolean: true },
      ],
      ...projectRules,
    },
  },
  // React Bits components, installed with the official CLI (ADR-023). They pass our strict
  // TypeScript compiler settings (a few marked `untangle:` fixes), but are linted with the standard
  // recommended rules rather than strictTypeChecked, so updates from upstream stay easy to merge.
  {
    files: [VENDORED],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: { ecmaVersion: 2023, globals: globals.browser },
    plugins: { 'react-hooks': reactHooks },
    rules: { ...reactHooks.configs.recommended.rules, ...projectRules },
  },
  {
    files: ['*.js'],
    extends: [js.configs.recommended],
    languageOptions: { globals: globals.node },
  },
  // Must be last: turns off stylistic rules that Prettier owns.
  prettier,
);

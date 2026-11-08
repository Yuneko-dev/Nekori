module.exports = {
  root: true,
  parser: '@typescript-eslint/parser',
  extends: ['eslint:recommended', 'plugin:@typescript-eslint/recommended'],
  env: { browser: true, es2022: true },
  parserOptions: { sourceType: 'module' },
  rules: {
    'eol-last': ['error', 'always'],
    'no-tabs': 'error',
    'no-trailing-spaces': 'error',
  },
};

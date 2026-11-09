module.exports = {
  root: true,
  extends: ['@react-native', 'prettier'],
  plugins: ['simple-import-sort', 'unused-imports'],
  rules: {
    '@react-native/no-deep-imports': 'off',
    'simple-import-sort/imports': 'error',
    'simple-import-sort/exports': 'error',
    'no-unused-vars': 'off',
    '@typescript-eslint/no-unused-vars': 'off',
    'unused-imports/no-unused-imports': 'error',
    'unused-imports/no-unused-vars': [
      'warn',
      {
        vars: 'all',
        varsIgnorePattern: '^_',
        args: 'after-used',
        argsIgnorePattern: '^_',
      },
    ],
    '@typescript-eslint/no-explicit-any': 'warn',
    'no-var': 'error',
    'prefer-const': 'error',
    'no-duplicate-imports': 'error',
    'no-new-func': 'off',
    'no-void': 'off',
    'no-async-promise-executor': 'error',
    'no-unsafe-optional-chaining': 'error',
  },
  overrides: [
    {
      files: ['src/plugins/helpers/fetch.ts'],
      rules: {
        'no-bitwise': 'off',
      },
    },
  ],
};

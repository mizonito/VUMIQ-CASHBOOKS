import firebaseRulesPlugin from '@firebase/eslint-plugin-security-rules';

export default [
  {
    ignores: ['dist/**/*', 'node_modules/**/*', 'android-app/**/*']
  },
  firebaseRulesPlugin.configs['flat/recommended']
];

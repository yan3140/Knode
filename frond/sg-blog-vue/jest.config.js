const babelConfig = {
  presets: [
    ['env', { modules: 'commonjs', targets: { node: 'current' } }],
    'stage-2'
  ],
  plugins: ['transform-vue-jsx', 'transform-runtime']
}

module.exports = {
  moduleFileExtensions: ['js', 'json', 'vue'],
  testEnvironment: 'jsdom',
  testMatch: ['<rootDir>/tests/unit/**/*.spec.js'],
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1'
  },
  transform: {
    '^.+\\.vue$': 'vue-jest',
    '^.+\\.js$': '<rootDir>/tests/babel6JestTransform.js'
  },
  globals: {
    'vue-jest': { babelConfig }
  }
}

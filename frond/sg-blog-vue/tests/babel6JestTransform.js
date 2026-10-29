const babel = require('babel-core')

module.exports = {
  process(source, filename) {
    return babel.transform(source, {
      filename,
      babelrc: false,
      presets: [
        ['env', { modules: 'commonjs', targets: { node: 'current' } }],
        'stage-2'
      ],
      plugins: ['transform-vue-jsx', 'transform-runtime']
    }).code
  }
}

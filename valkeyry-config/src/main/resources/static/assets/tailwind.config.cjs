/** Source of truth for the Tailwind build that powers login.html / admin.html / tools.html.
 *  Mirror of /tmp/tw/tailwind.config.cjs — kept inside the repo so the build is reproducible.
 *
 *  Build command (see assets/README.md):
 *    cd /tmp/tw && npx tailwindcss -c assets/tailwind.config.cjs -i assets/tailwind.src.css \
 *        -o assets/tailwind.css --minify
 */
module.exports = {
  content: [
    '/app/valkeyry-config/src/main/resources/static/login.html',
    '/app/valkeyry-config/src/main/resources/static/admin.html',
    '/app/valkeyry-config/src/main/resources/static/tools.html',
  ],
  corePlugins: { preflight: true },
  theme: {
    extend: {
      fontFamily: {
        sans: ['-apple-system', 'BlinkMacSystemFont', '"SF Pro Display"', 'Inter', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
};

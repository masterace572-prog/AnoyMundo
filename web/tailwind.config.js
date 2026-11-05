/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './views/**/*.hbs',
    './public/**/*.html',
    './src/**/*.mjs',
  ],
  safelist: [
    'bg-purple-600',
    'from-green-50', 'to-teal-50', 'border-green-500', 'text-green-800',
    'from-red-50', 'to-pink-50', 'border-red-500', 'text-red-800',
    'from-yellow-50', 'to-orange-50', 'border-yellow-500', 'text-yellow-800',
    'from-blue-50', 'to-indigo-50', 'border-blue-500', 'text-blue-800',
    'from-purple-50', 'to-pink-50', 'border-purple-500', 'text-purple-800',
  ],
  theme: { extend: {} },
  plugins: [],
};

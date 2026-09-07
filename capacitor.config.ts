import { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.cinemanual.a56',
  appName: 'Cine Manual A56',
  webDir: 'www',
  // Fundo transparente: a pré-visualização real da câmera (nativa, por
  // baixo da WebView) precisa aparecer através da interface HTML.
  backgroundColor: '#00000000',
  android: {
    backgroundColor: '#00000000',
    allowMixedContent: false
  }
};

export default config;

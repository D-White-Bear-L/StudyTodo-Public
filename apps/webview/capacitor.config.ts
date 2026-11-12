import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'host.workbuddy.dailytasklist',
  appName: '每日任务清单',
  webDir: 'dist',
  android: {
    allowMixedContent: false,
    backgroundColor: '#E8EDF3'
  },
  server: {
    androidScheme: 'https'
  }
};

export default config;

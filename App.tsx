/**
 * PhoneGuard - 骚扰电话拦截应用
 * 
 * 主要功能:
 * - 检测来电是否为营销/骚扰电话
 * - 自动拦截或挂断垃圾电话
 * - 黑白名单管理
 * - 拦截历史记录
 */

import React from 'react';
import { SafeAreaView, StatusBar, StyleSheet } from 'react-native';
import HomeScreen from './src/screens/HomeScreen';

const App: React.FC = () => {
  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="dark-content" backgroundColor="#fff" />
      <HomeScreen />
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
  },
});

export default App;

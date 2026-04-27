import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Switch,
  ScrollView,
  Alert,
  TextInput,
} from 'react-native';
import { AppSettings, CallEvent } from '../types';
import { StorageService, PhoneService, LocationService } from '../services';
import CallDetection from '../native/modules/CallDetection';

const HomeScreen: React.FC = () => {
  const [settings, setSettings] = useState<AppSettings | null>(null);
  const [isListening, setIsListening] = useState(false);
  const [blockedCount, setBlockedCount] = useState(0);
  const [localProvince, setLocalProvince] = useState<string>('');
  const [provinceInput, setProvinceInput] = useState<string>('');

  useEffect(() => {
    loadSettings();
    loadStats();
    loadLocalLocation();

    // Subscribe to call events
    const unsubscribe = CallDetection.onCallEvent(handleCallEvent);
    return () => {
      unsubscribe();
    };
  }, []);

  const handleCallEvent = async (event: CallEvent) => {
    console.log('[HomeScreen] Call event:', event);

    if (event.eventType === 'incoming') {
      try {
        // Check if this number should be blocked
        const markResult = await PhoneService.checkPhoneNumber(
          event.phoneNumber,
        );
        const location = await PhoneService.getPhoneLocation(event.phoneNumber);

        const shouldBlock = await PhoneService.shouldBlockNumber(
          event.phoneNumber,
          markResult,
        );

        if (shouldBlock) {
          try {
            await CallDetection.endCall();
            setBlockedCount(prev => prev + 1);

            if (settings?.enableNotification) {
              Alert.alert('已拦截', `已拦截号码: ${event.phoneNumber}`);
            }
          } catch (blockError: any) {
            // Still count it as a detected spam number
            setBlockedCount(prev => prev + 1);

            if (settings?.enableNotification) {
              Alert.alert(
                '检测到骚扰号码',
                `号码: ${event.phoneNumber}\n归属地: ${location}\n\n注意: 由于 Android 系统限制，无法自动挂断此电话。\n请在系统设置中将本应用设为默认电话应用以启用自动拦截。`,
              );
            }
          }
        }
      } catch (error) {
        // Silently handle errors to not disrupt user experience
      }
    }
  };

  const loadLocalLocation = () => {
    const location = PhoneService.getLocalLocation();
    if (location) {
      setLocalProvince(location);
      setProvinceInput(location);
    }
  };

  const loadSettings = async () => {
    const savedSettings = await StorageService.getSettings();
    setSettings(savedSettings);
  };

  const loadStats = async () => {
    // TODO: 加载拦截统计
    setBlockedCount(0);
  };

  const syncToNative = () => {
    if (!settings) return;
    CallDetection.syncSettings({
      localProvince: localProvince || '',
      autoBlock: settings.autoBlock ?? false,
      whitelist: settings.whitelist ?? [],
      blacklist: settings.blacklist ?? [],
    });
  };

  const toggleProtection = async () => {
    if (!settings) return;

    try {
      if (!isListening) {
        // Sync current settings to native service before starting
        syncToNative();
        await CallDetection.startListening();
        setIsListening(true);
        Alert.alert('防护已开启', '正在监控来电...');
      } else {
        await CallDetection.stopListening();
        setIsListening(false);
        Alert.alert('防护已关闭');
      }
    } catch (error) {
      Alert.alert('错误', '无法切换防护状态，请检查权限');
    }
  };

  const updateSetting = async (key: keyof AppSettings, value: any) => {
    if (!settings) return;

    const newSettings = { ...settings, [key]: value };
    setSettings(newSettings);
    await StorageService.saveSettings(newSettings);

    // Sync updated setting to native service
    CallDetection.syncSettings({
      localProvince: localProvince || '',
      autoBlock: newSettings.autoBlock ?? false,
      whitelist: newSettings.whitelist ?? [],
      blacklist: newSettings.blacklist ?? [],
    });
  };

  const saveLocation = async () => {
    if (!provinceInput.trim()) {
      Alert.alert('提示', '请输入省份名称');
      return;
    }

    PhoneService.setLocalLocation(provinceInput.trim());
    setLocalProvince(provinceInput.trim());
    Alert.alert('保存成功', `本机归属地已设置为：${provinceInput.trim()}`);

    // Sync updated province to native service
    CallDetection.syncSettings({
      localProvince: provinceInput.trim(),
      autoBlock: settings?.autoBlock ?? false,
      whitelist: settings?.whitelist ?? [],
      blacklist: settings?.blacklist ?? [],
    });
  };

  if (!settings) {
    return (
      <View style={styles.container}>
        <Text>加载中...</Text>
      </View>
    );
  }

  return (
    <ScrollView style={styles.container}>
      {/* 状态卡片 */}
      <View style={styles.statusCard}>
        <Text style={styles.statusTitle}>防护状态</Text>
        <TouchableOpacity
          style={[
            styles.statusButton,
            isListening ? styles.statusActive : styles.statusInactive,
          ]}
          onPress={toggleProtection}
        >
          <Text style={styles.statusButtonText}>
            {isListening ? '防护中' : '已关闭'}
          </Text>
        </TouchableOpacity>
        <Text style={styles.statsText}>已拦截 {blockedCount} 个骚扰电话</Text>
      </View>

      {/* 归属地设置 */}
      <View style={styles.settingsSection}>
        <Text style={styles.sectionTitle}>归属地设置</Text>
        <Text style={styles.description}>
          当前本机归属地：{localProvince || '未设置'}
        </Text>
        <Text style={styles.description}>非本省号码将被标记为疑似营销号</Text>

        <View style={styles.inputRow}>
          <TextInput
            style={styles.input}
            placeholder="输入省份，如：北京"
            value={provinceInput}
            onChangeText={setProvinceInput}
          />
          <TouchableOpacity style={styles.saveButton} onPress={saveLocation}>
            <Text style={styles.saveButtonText}>保存</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* 设置选项 */}
      <View style={styles.settingsSection}>
        <Text style={styles.sectionTitle}>设置</Text>

        {/* 自动拦截 */}
        <View style={styles.settingItem}>
          <Text style={styles.settingLabel}>自动拦截外地号码</Text>
          <Switch
            value={settings.autoBlock ?? false}
            onValueChange={value => updateSetting('autoBlock', value)}
          />
        </View>

        {/* 通知 */}
        <View style={styles.settingItem}>
          <Text style={styles.settingLabel}>拦截通知</Text>
          <Switch
            value={settings.enableNotification ?? false}
            onValueChange={value => updateSetting('enableNotification', value)}
          />
        </View>

        {/* 通话记录 */}
        <View style={styles.settingItem}>
          <Text style={styles.settingLabel}>记录拦截历史</Text>
          <Switch
            value={settings.enableLog ?? false}
            onValueChange={value => updateSetting('enableLog', value)}
          />
        </View>
      </View>

      {/* 黑白名单入口 */}
      <View style={styles.settingsSection}>
        <Text style={styles.sectionTitle}>名单管理</Text>

        <TouchableOpacity style={styles.listButton}>
          <Text style={styles.listButtonText}>
            白名单 ({(settings.whitelist ?? []).length})
          </Text>
        </TouchableOpacity>

        <TouchableOpacity style={styles.listButton}>
          <Text style={styles.listButtonText}>
            黑名单 ({(settings.blacklist ?? []).length})
          </Text>
        </TouchableOpacity>
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  statusCard: {
    backgroundColor: '#fff',
    margin: 16,
    padding: 24,
    borderRadius: 12,
    alignItems: 'center',
  },
  statusTitle: {
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 16,
  },
  statusButton: {
    paddingHorizontal: 48,
    paddingVertical: 12,
    borderRadius: 24,
    marginBottom: 12,
  },
  statusActive: {
    backgroundColor: '#4CAF50',
  },
  statusInactive: {
    backgroundColor: '#9E9E9E',
  },
  statusButtonText: {
    color: '#fff',
    fontSize: 18,
    fontWeight: '600',
  },
  statsText: {
    color: '#666',
    fontSize: 14,
  },
  description: {
    color: '#999',
    fontSize: 13,
    marginBottom: 8,
  },
  inputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  input: {
    flex: 1,
    borderWidth: 1,
    borderColor: '#ddd',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
    fontSize: 15,
  },
  saveButton: {
    backgroundColor: '#007AFF',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
  },
  saveButtonText: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '600',
  },
  settingsSection: {
    backgroundColor: '#fff',
    marginHorizontal: 16,
    marginBottom: 16,
    borderRadius: 12,
    padding: 16,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 16,
  },
  settingItem: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  settingLabel: {
    fontSize: 15,
  },
  listButton: {
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  listButtonText: {
    fontSize: 15,
  },
});

export default HomeScreen;

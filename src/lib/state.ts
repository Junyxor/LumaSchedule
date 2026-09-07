import type { Course, GlassSettings } from './types';

export const courses: Course[] = [
  { id: 'math', name: '高等数学', teacher: '陈老师', room: '教6-204', start: '08:30', end: '10:05', day: 1, color: 'violet', startSection: 1, endSection: 2, weeks: Array.from({ length: 16 }, (_, i) => i + 1) },
  { id: 'c', name: 'C语言程序设计', teacher: '林老师', room: '实验4-302', start: '10:25', end: '12:00', day: 1, color: 'cyan', startSection: 3, endSection: 4, weeks: Array.from({ length: 16 }, (_, i) => i + 1) },
  { id: 'english', name: '大学英语', teacher: '周老师', room: '教3-106', start: '13:50', end: '15:25', day: 1, color: 'amber', startSection: 5, endSection: 6, weeks: Array.from({ length: 16 }, (_, i) => i + 1) },
  { id: 'physics', name: '大学物理', teacher: '许老师', room: '教6-401', start: '08:30', end: '10:05', day: 2, color: 'blue', startSection: 1, endSection: 2, weeks: Array.from({ length: 16 }, (_, i) => i + 1) },
  { id: 'quantum', name: '量子信息导论', teacher: '李老师', room: '理学馆A302', start: '10:25', end: '12:00', day: 3, color: 'pink', startSection: 3, endSection: 4, weeks: Array.from({ length: 16 }, (_, i) => i + 1) },
  { id: 'pe', name: '体育', teacher: '梁老师', room: '大学城体育场', start: '15:30', end: '17:15', day: 4, color: 'green', startSection: 7, endSection: 8, weeks: Array.from({ length: 16 }, (_, i) => i + 1) },
  { id: 'career', name: '大学生职业规划', teacher: '王老师', room: '教2-205', start: '18:30', end: '20:05', day: 5, color: 'orange', startSection: 10, endSection: 11, weeks: [1,3,5,7,9,11,13,15] }
];
export const defaultGlass: GlassSettings = { blur: 24, opacity: 72, saturation: 128, highlight: 62, refraction: 38, noise: 3, motion: true };

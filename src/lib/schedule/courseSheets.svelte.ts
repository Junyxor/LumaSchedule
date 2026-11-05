import type { Course, CourseMutation } from '../types';

interface DetailState {
  course: Course;
  /** The calendar date the user tapped, if any. */
  date: Date | null;
}

interface EditorState {
  course: Course | null;
  preset: Partial<CourseMutation>;
}

class CourseSheets {
  detail = $state.raw<DetailState | null>(null);
  editor = $state.raw<EditorState | null>(null);

  show(course: Course, date: Date | null = null) {
    this.detail = { course, date };
  }

  edit(course: Course) {
    this.detail = null;
    this.editor = { course, preset: {} };
  }

  create(preset: Partial<CourseMutation> = {}) {
    this.detail = null;
    this.editor = { course: null, preset };
  }

  closeDetail() {
    this.detail = null;
  }

  closeEditor() {
    this.editor = null;
  }
}

export const courseSheets = new CourseSheets();

export const COURSE_COLORS = ['violet', 'blue', 'cyan', 'teal', 'green', 'lime', 'amber', 'orange', 'red', 'pink'] as const;

export function toneClass(course: Pick<Course, 'color'>, colorful: boolean) {
  return colorful && (COURSE_COLORS as readonly string[]).includes(course.color) ? `tone-${course.color}` : 'tone-neutral';
}

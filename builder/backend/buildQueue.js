'use strict';

const EventEmitter = require('events');

// Сколько сборок одновременно. 3 = ~6 GB RAM (2 GB heap × 3).
// Уменьши до 2 если меньше 8 GB RAM.
const CONCURRENCY = 6;

class BuildQueue extends EventEmitter {
  constructor() {
    super();
    this.queue   = [];
    this.running = new Set(); // id → job
    this.history = [];
  }

  enqueue(job) {
    this.queue.push(job);
    this.emit('queued', { id: job.id, position: this.queue.length });
    this._tryNext();
  }

  _tryNext() {
    while (this.running.size < CONCURRENCY && this.queue.length > 0) {
      const job = this.queue.shift();
      this.running.add(job);
      this.emit('started', { id: job.id, project: job.project, flags: job.flags });

      job.runner()
        .then(result => {
          this.history.unshift({ ...job, result, endedAt: Date.now() });
          if (this.history.length > 200) this.history.pop();
          this.emit('finished', { id: job.id, success: true, apkPath: result.apkPath });
        })
        .catch(err => {
          this.history.unshift({ ...job, result: { error: err.message }, endedAt: Date.now() });
          if (this.history.length > 200) this.history.pop();
          this.emit('finished', { id: job.id, success: false, error: err.message });
        })
        .finally(() => {
          this.running.delete(job);
          this._tryNext();
        });
    }
  }

  status() {
    return {
      running: [...this.running].map(j => ({ id: j.id, project: j.project })),
      queued:  this.queue.map(j => ({ id: j.id, project: j.project })),
      history: this.history.slice(0, 50).map(j => ({
        id:      j.id,
        project: j.project,
        success: j.result && !j.result.error,
        endedAt: j.endedAt,
        apkPath: j.result && j.result.apkPath,
        error:   j.result && j.result.error,
      })),
    };
  }
}

module.exports = new BuildQueue();

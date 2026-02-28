package com.lifthelper.di

import com.lifthelper.domain.feedback.IFeedbackEmitter
import com.lifthelper.feedback.FeedbackManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FeedbackModule {

    @Binds
    @Singleton
    abstract fun bindFeedbackEmitter(impl: FeedbackManager): IFeedbackEmitter
}
